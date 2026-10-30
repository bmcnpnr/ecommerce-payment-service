package com.ecommerce.payment.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentMethod;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.outbox.OutboxRelay;
import com.ecommerce.payment.repository.PaymentRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The headline promise of the outbox: with Kafka unreachable, a payment can still be refunded, promptly;
 * the event waits in the table, the relay backs off and stays quiet, and the event is published once
 * (not lost, not twice) when the broker comes back.
 *
 * <p>The broker is frozen with {@code docker pause} and thawed afterwards. It is this class's own
 * Kafka (and Postgres), so the other integration tests, which share one broker, never see the outage.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("it")
class PaymentOutboxKafkaOutageIT {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");
    static final ConfluentKafkaContainer KAFKA = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.6.0");

    static {
        Startables.deepStart(POSTGRES, KAFKA).join();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @AfterAll
    static void thaw() {
        try {
            DockerClientFactory.lazyClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
        } catch (RuntimeException alreadyRunning) {
            // not paused: fine
        }
    }

    @LocalServerPort int port;
    @Autowired PaymentRepository payments;
    @Autowired JdbcTemplate jdbc;

    private ResponseEntity<String> refund(long paymentId) {
        return RestClient.builder().baseUrl("http://localhost:" + port).requestFactory(new JdkClientHttpRequestFactory()).build()
                .post().uri("/api/v1/payments/" + paymentId + "/refund")
                .headers(h -> {
                    h.set("X-Username", "root");
                    h.set("X-User-Role", "ADMIN");
                })
                .retrieve().onStatus(HttpStatusCode::isError, (rq, rs) -> { }).toEntity(String.class);
    }

    private List<ConsumerRecord<String, String>> recordsWithKey(String topic, String key, Duration window) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outage-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                consumer.poll(Duration.ofMillis(300)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
            }
        }
        return found;
    }

    @Test
    void withKafkaUnreachable_refundStillReturnsPromptly_theEventWaits_andIsPublishedOnceWhenKafkaIsBack() {
        long orderId = ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
        Payment payment = payments.saveAndFlush(Payment.builder().orderId(orderId).customerId("cust-outage")
                .amount(new BigDecimal("10.00")).currency("USD").method(PaymentMethod.CREDIT_CARD).status(PaymentStatus.COMPLETED)
                .transactionId("TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase()).build());
        String key = Long.toString(orderId);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        relayLogger.addAppender(logs);
        try {
            DockerClientFactory.lazyClient().pauseContainerCmd(KAFKA.getContainerId()).exec();

            long start = System.nanoTime();
            ResponseEntity<String> refunded = refund(payment.getId());
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            // the request does not wait for Kafka (it used to block for max.block.ms = 60 s)
            assertThat(refunded.getStatusCode().value()).as(refunded.getBody()).isEqualTo(200);
            assertThat(elapsedMs).as("refund while Kafka is down").isLessThan(3000);
            assertThat(jdbc.queryForObject("select status from payments where id = ?", String.class, payment.getId())).isEqualTo("REFUNDED");

            // the relay keeps trying (each attempt gives up after max.block.ms = 5 s) and the row stays
            await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("select attempts from outbox_event where event_key = ?", Integer.class, key)).isGreaterThanOrEqualTo(2));
            assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_key = ?", Long.class, key)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select last_error from outbox_event where event_key = ?", String.class, key)).contains("Exception");
            List<ILoggingEvent> loud = logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
            assertThat(loud).as("relay lines while Kafka is down: one WARN, no stack trace").hasSize(1);
            assertThat(loud.get(0).getThrowableProxy()).isNull();
            assertThat(loud.get(0).getFormattedMessage()).contains("payment.refunded").contains("retrying in");

            DockerClientFactory.lazyClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();

            await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("select count(*) from outbox_event where event_key = ?", Long.class, key)).isZero());
            List<ConsumerRecord<String, String>> published = recordsWithKey("payment.refunded", key, Duration.ofSeconds(5));
            assertThat(published).as("published exactly once").hasSize(1);
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.INFO)
                    .anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("resumed after").contains("failed attempts"));
        } finally {
            relayLogger.detachAppender(logs);
            thaw();
        }
    }
}
