package com.ecommerce.payment.integration;

import com.sun.net.httpserver.HttpServer;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared Testcontainers for every *IT class: one Postgres and one Kafka per JVM (started once in
 * the static initializer, reused by all IT classes and Spring contexts, removed by Ryuk at JVM exit).
 * Images match production (docker-compose / k8s).
 *
 * <p>Postgres is wired through {@link ServiceConnection}. Kafka is not: this service uses plain
 * spring-kafka without Boot's Kafka auto-configuration, and {@code KafkaProducerConfig} reads
 * {@code spring.kafka.bootstrap-servers} itself, so the property is set directly.
 */
@ActiveProfiles("it")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    protected static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");

    /** Stand-in for order-service: order id to the OrderDTO JSON it would answer with (404 when absent). */
    protected static final Map<Long, String> FAKE_ORDERS = new ConcurrentHashMap<>();
    /** When set, the stand-in answers every lookup with this status instead (to simulate an outage). */
    protected static volatile int fakeOrderServiceForcedStatus = 0;
    /** How long the stand-in takes to answer (to simulate a slow order-service), and how many lookups reached it. */
    protected static volatile long fakeOrderServiceDelayMs = 0;
    protected static final AtomicInteger FAKE_ORDER_LOOKUPS = new AtomicInteger();
    protected static final HttpServer FAKE_ORDER_SERVICE;

    static {
        POSTGRES.start();
        KAFKA.start();
        try {
            FAKE_ORDER_SERVICE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        // The default executor runs one handler at a time, which would serialise the slow answers below.
        FAKE_ORDER_SERVICE.setExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "fake-order-service");
            t.setDaemon(true);
            return t;
        }));
        FAKE_ORDER_SERVICE.createContext("/internal/v1/orders/", exchange -> {
            FAKE_ORDER_LOOKUPS.incrementAndGet();
            if (fakeOrderServiceDelayMs > 0) {
                try {
                    Thread.sleep(fakeOrderServiceDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            String id = exchange.getRequestURI().getPath().substring("/internal/v1/orders/".length());
            String json = id.matches("\\d+") ? FAKE_ORDERS.get(Long.parseLong(id)) : null;
            int status = fakeOrderServiceForcedStatus != 0 ? fakeOrderServiceForcedStatus : (json == null ? 404 : 200);
            byte[] bytes = (json == null || status != 200 ? "{}" : json).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        FAKE_ORDER_SERVICE.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> FAKE_ORDER_SERVICE.stop(0)));
    }

    /**
     * The tests share one Spring context, and some of them make order-service fail on purpose. A circuit breaker
     * that one test left open (or half-full of failures) would refuse the next test's payments.
     */
    @Autowired(required = false)
    private CircuitBreakerRegistry circuitBreakers;

    @BeforeEach
    void closeCircuitBreakers() {
        if (circuitBreakers != null) {
            circuitBreakers.getAllCircuitBreakers().forEach(CircuitBreaker::reset);
        }
    }

    /** Reads {@code topic} from the beginning (fresh group) until a record with {@code key} arrives. */
    protected static ConsumerRecord<String, String> awaitRecord(String topic, String key, Duration timeout) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("No record with key " + key + " on " + topic + " within " + timeout);
    }

    /** Registers an order as order-service's internal endpoint would return it. */
    protected static void registerOrder(long orderId, String customerId, String total, String status) {
        FAKE_ORDERS.put(orderId, tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(Map.of(
                "orderId", orderId, "customerId", customerId, "totalAmount", new java.math.BigDecimal(total),
                "status", status, "orderItems", java.util.List.of())));
    }

    @DynamicPropertySource
    static void orderServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri",
                () -> "http://127.0.0.1:" + FAKE_ORDER_SERVICE.getAddress().getPort());
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
