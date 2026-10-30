package com.ecommerce.payment.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.payment.repository.PaymentRepository;
import com.zaxxer.hikari.HikariDataSource;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * How the payment service behaves around order-service, the one service it must ask before it accepts a
 * payment (H1, H2): a slow peer does not hold database connections or cut healthy answers short, a dead
 * one is refused quickly with a 503 and a handful of log lines, and nothing is retried behind its back.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderServiceResilienceIT extends AbstractIntegrationTest {

    @LocalServerPort int port;
    @Autowired CircuitBreakerRegistry breakers;
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;
    @Autowired PaymentRepository repository;

    private final ExecutorService clients = Executors.newFixedThreadPool(40);
    private RestClient http;

    @BeforeEach
    void client() {
        // The JDK client never re-sends a request on its own (Apache's re-sends a 503), and has no 5-connection cap.
        http = RestClient.builder().baseUrl("http://localhost:" + port)
                .requestFactory(new JdkClientHttpRequestFactory())
                .defaultHeader("X-Username", "root").defaultHeader("X-User-Id", "1").defaultHeader("X-User-Role", "ADMIN")
                .build();
        FAKE_ORDER_LOOKUPS.set(0);
        fakeOrderServiceDelayMs = 0;
        fakeOrderServiceForcedStatus = 0;
        breakers.circuitBreaker("order-service").reset();
    }

    @AfterEach
    void restore() {
        fakeOrderServiceDelayMs = 0;
        fakeOrderServiceForcedStatus = 0;
        breakers.circuitBreaker("order-service").reset();
        clients.shutdownNow();
    }

    private static long newOrderId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private ResponseEntity<String> initiate(long orderId) {
        return initiate(orderId, "cust-resilience");
    }

    private ResponseEntity<String> initiate(long orderId, String customer) {
        return http.post().uri("/api/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {"orderId":%d,"customerId":"%s","amount":10.00,"method":"CREDIT_CARD"}""".formatted(orderId, customer))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    // ---- the Spring Cloud circuit breaker must not impose limits nobody asked for ----

    @Test
    void theBreakerAddsNoBulkheadAndNoWorkerThread() {
        assertThat(context.getBeanProvider(Resilience4jBulkheadProvider.class).getIfAvailable())
                .as("spring.cloud.circuitbreaker.bulkhead.resilience4j.enabled=false").isNull();
    }

    /** Resilience4j's time limiter defaults to 1 s and would turn every slower (but healthy) answer into a failure. */
    @Test
    void aSlowButHealthyOrderService_isNotCutOffByTheDefaultOneSecondTimeLimiter() {
        long orderId = newOrderId();
        registerOrder(orderId, "cust-resilience", "10.00", "CONFIRMED");
        fakeOrderServiceDelayMs = 2500;

        long start = System.nanoTime();
        ResponseEntity<String> response = initiate(orderId);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        assertThat(elapsedMs).isGreaterThanOrEqualTo(2400);
    }

    // ---- a slow order-service must not exhaust the pool (H2) ----

    @Test
    void aSlowOrderService_doesNotPinDatabaseConnections_soTheRestOfTheServiceStaysResponsive() throws Exception {
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 20; i++) {   // twice the pool size (10)
            long orderId = newOrderId();
            registerOrder(orderId, "cust-resilience", "10.00", "CONFIRMED");
            orders.add(orderId);
        }
        long existingOrder = newOrderId();
        registerOrder(existingOrder, "cust-probe", "10.00", "CONFIRMED");
        ResponseEntity<String> created = initiate(existingOrder, "cust-probe");
        assertThat(created.getStatusCode().value()).as(created.getBody()).isEqualTo(201);
        long probePaymentId = tools.jackson.databind.json.JsonMapper.builder().build().readTree(created.getBody()).get("id").asLong();
        HikariDataSource pool = dataSource.unwrap(HikariDataSource.class);
        fakeOrderServiceDelayMs = 3000;

        List<CompletableFuture<ResponseEntity<String>>> slow = orders.stream()
                .map(id -> CompletableFuture.supplyAsync(() -> initiate(id), clients)).toList();
        Thread.sleep(1000); // all twenty are now waiting on order-service

        int busiest = 0;
        for (int i = 0; i < 5; i++) {
            busiest = Math.max(busiest, pool.getHikariPoolMXBean().getActiveConnections());
            Thread.sleep(100);
        }
        long start = System.nanoTime();
        ResponseEntity<String> probe = http.get().uri("/api/v1/payments/" + probePaymentId)
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
        long probeMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(busiest).as("pooled connections held while 20 requests wait for order-service").isLessThanOrEqualTo(2);
        assertThat(probe.getStatusCode().value()).isEqualTo(200);
        assertThat(probeMs).as("an unrelated read while 20 slow requests are in flight").isLessThan(1000);
        for (CompletableFuture<ResponseEntity<String>> call : slow) {
            assertThat(call.get().getStatusCode().value()).as(call.get().getBody()).isEqualTo(201);
        }
    }

    // ---- no hidden retries, a breaker that counts the right things ----

    @Test
    void anOrderServiceThatAnswers503_isAskedExactlyOncePerPayment() {
        long orderId = newOrderId();
        registerOrder(orderId, "cust-resilience", "10.00", "CONFIRMED");
        fakeOrderServiceForcedStatus = 503;

        ResponseEntity<String> response = initiate(orderId);

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(FAKE_ORDER_LOOKUPS.get()).as("lookups that reached order-service").isEqualTo(1);
    }

    @Test
    void unknownOrders_are404_andNeverTripTheBreaker() {
        for (int i = 0; i < 30; i++) {
            assertThat(initiate(newOrderId()).getStatusCode().value()).isEqualTo(404);
        }

        CircuitBreaker breaker = breakers.circuitBreaker("order-service");
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(FAKE_ORDER_LOOKUPS.get()).isEqualTo(30);
    }

    @Test
    void aDeadOrderService_opensTheBreaker_whichThenRefusesFast_logsAHandfulOfLines_andRecovers() {
        long healthyOrder = newOrderId();
        registerOrder(healthyOrder, "cust-resilience", "10.00", "CONFIRMED");
        fakeOrderServiceForcedStatus = 500;
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger ours = (Logger) LoggerFactory.getLogger("com.ecommerce.payment");
        ours.addAppender(logs);
        try {
            List<Integer> statuses = new ArrayList<>();
            for (int i = 0; i < 60; i++) {
                statuses.add(initiate(healthyOrder).getStatusCode().value());
            }

            assertThat(statuses).as("every payment is refused, none accepted blind").containsOnly(503);
            assertThat(FAKE_ORDER_LOOKUPS.get()).as("lookups that reached the dead order-service (the rest failed fast)").isEqualTo(5);
            assertThat(breakers.circuitBreaker("order-service").getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(repository.findByOrderId(healthyOrder)).isEmpty();

            List<ILoggingEvent> loud = logs.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).toList();
            assertThat(loud).as("60 refused payments").hasSizeLessThanOrEqualTo(5);
            assertThat(loud).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
            assertThat(loud).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("Circuit breaker 'order-service'").contains("OPEN"));

            fakeOrderServiceForcedStatus = 0;
            await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(500)).untilAsserted(() -> {
                assertThat(initiate(healthyOrder).getStatusCode().value()).isIn(201, 409);
                assertThat(breakers.circuitBreaker("order-service").getState()).isEqualTo(CircuitBreaker.State.CLOSED);
            });
            assertThat(logs.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("-> CLOSED"));
        } finally {
            ours.detachAppender(logs);
        }
    }

    /** Slow is a failure mode too: every call that takes the full read timeout holds a request thread. */
    @Test
    void anOrderServiceThatAnswersTooSlowly_opensTheBreaker_andLaterPaymentsAreRefusedFast() throws Exception {
        List<Long> orders = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            long orderId = newOrderId();
            registerOrder(orderId, "cust-resilience", "10.00", "CONFIRMED");
            orders.add(orderId);
        }
        fakeOrderServiceDelayMs = 4300; // over the 4 s slow-call threshold, under the 5 s read timeout

        // five slow (but successful) answers fill the window, 100 % of them over the threshold
        List<CompletableFuture<ResponseEntity<String>>> slow = orders.subList(0, 5).stream()
                .map(id -> CompletableFuture.supplyAsync(() -> initiate(id), clients)).toList();
        for (CompletableFuture<ResponseEntity<String>> call : slow) {
            assertThat(call.get().getStatusCode().value()).as(call.get().getBody()).isEqualTo(201);
        }
        assertThat(breakers.circuitBreaker("order-service").getState()).isEqualTo(CircuitBreaker.State.OPEN);

        long start = System.nanoTime();
        ResponseEntity<String> refused = initiate(orders.get(5));
        long refusedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(refused.getStatusCode().value()).isEqualTo(503);
        assertThat(refusedMs).as("refused by the open breaker, not after another 4 s").isLessThan(1000);
        assertThat(FAKE_ORDER_LOOKUPS.get()).isEqualTo(5);
    }

    @Test
    void theBreakerMetricsAreExported() {
        long orderId = newOrderId();
        registerOrder(orderId, "cust-resilience", "10.00", "CONFIRMED");
        initiate(orderId);

        String scrape = http.get().uri("/actuator/prometheus").retrieve().body(String.class);

        assertThat(scrape).contains("resilience4j_circuitbreaker_state").contains("name=\"order-service\"");
    }
}
