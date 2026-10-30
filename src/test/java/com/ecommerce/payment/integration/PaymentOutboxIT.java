package com.ecommerce.payment.integration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.payment.event.PaymentRefundedEvent;
import com.ecommerce.payment.messaging.PaymentEventProducer;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentMethod;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.outbox.OutboxEvent;
import com.ecommerce.payment.outbox.OutboxEventRepository;
import com.ecommerce.payment.outbox.OutboxRelay;
import com.ecommerce.payment.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * The transactional outbox against real Postgres and Kafka: an event exists exactly when the change that
 * caused it commits, and the relay delivers it afterwards, once, in order. (Kafka being unreachable is
 * {@code PaymentOutboxKafkaOutageIT}.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentOutboxIT extends AbstractIntegrationTest {

    private static final Duration KAFKA_TIMEOUT = Duration.ofSeconds(30);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    @Autowired OutboxEventRepository outbox;
    @Autowired PaymentEventProducer producer;
    @Autowired PaymentRepository payments;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired OutboxRelay relay; // present: the IT profile runs the real relay

    private static long newOrderId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private long outboxRows(String key) {
        return jdbc.queryForObject("select count(*) from outbox_event where event_key = ?", Long.class, key);
    }

    private static PaymentRefundedEvent refunded(long orderId) {
        return PaymentRefundedEvent.builder().paymentId(1L).orderId(orderId).customerId("cust-outbox")
                .amount(new BigDecimal("10.00")).refundedAt(LocalDateTime.now()).build();
    }

    @Test
    void refundingAPayment_publishesPaymentRefunded_andTheRowIsGoneOnceKafkaAcknowledged() {
        long orderId = newOrderId();
        Payment payment = payments.saveAndFlush(Payment.builder().orderId(orderId).customerId("cust-outbox-1")
                .amount(new BigDecimal("10.00")).currency("USD").method(PaymentMethod.CREDIT_CARD).status(PaymentStatus.COMPLETED)
                .transactionId("TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase()).build());

        var refund = RestClient.builder().baseUrl("http://localhost:" + port).requestFactory(new JdkClientHttpRequestFactory()).build()
                .post().uri("/api/v1/payments/" + payment.getId() + "/refund")
                .headers(h -> {
                    h.set("X-Username", "root");
                    h.set("X-User-Role", "ADMIN");
                })
                .retrieve().toEntity(String.class);

        assertThat(refund.getStatusCode().value()).isEqualTo(200);
        JsonNode event = JSON.readTree(awaitRecord("payment.refunded", Long.toString(orderId), KAFKA_TIMEOUT).value());
        assertThat(event.get("paymentId").asLong()).isEqualTo(payment.getId());
        assertThat(event.get("refundedAt").isArray()).as("LocalDateTime stays an int array on the wire").isTrue();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(outboxRows(Long.toString(orderId))).isZero());
        assertThat(jdbc.queryForObject("select status from payments where id = ?", String.class, payment.getId())).isEqualTo("REFUNDED");
    }

    @Test
    void theEventIsInvisibleUntilTheChangeCommits_andDeliveredOnceAfterwards() throws Exception {
        long orderId = newOrderId();
        String key = Long.toString(orderId);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        var otherConnection = Executors.newSingleThreadExecutor();
        try {
            tx.executeWithoutResult(status -> {
                producer.publishRefunded(refunded(orderId));
                assertThat(outboxRows(key)).as("visible inside the writing transaction").isEqualTo(1);
                long seenElsewhere;
                try {
                    // another thread has no transaction bound, so it reads on its own connection
                    seenElsewhere = otherConnection.submit(() ->
                            jdbc.queryForObject("select count(*) from outbox_event where event_key = ?", Long.class, key)).get();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                assertThat(seenElsewhere).as("not visible to the relay before commit").isZero();
            });
        } finally {
            otherConnection.shutdown();
        }

        assertThat(awaitRecord("payment.refunded", key, KAFKA_TIMEOUT).key()).isEqualTo(key);
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(outboxRows(key)).isZero());
    }

    @Test
    void aRolledBackTransaction_publishesNothing() {
        long orderId = newOrderId();
        String key = Long.toString(orderId);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            payments.save(Payment.builder().orderId(orderId).customerId("cust-rollback").amount(BigDecimal.TEN)
                    .method(PaymentMethod.CREDIT_CARD).status(PaymentStatus.COMPLETED).build());
            producer.publishRefunded(refunded(orderId));
            status.setRollbackOnly();
        });

        assertThat(outboxRows(key)).isZero();
        assertThat(payments.findByOrderId(orderId)).isEmpty();
        assertThatThrownBy(() -> awaitRecord("payment.refunded", key, Duration.ofSeconds(4)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("No record with key " + key);
    }

    @Test
    void aRowThatCanNeverBePublished_isParkedOnce_andTheRowBehindItIsStillDelivered() {
        String poisonKey = "poison-" + UUID.randomUUID().toString().substring(0, 8);
        String healthyKey = "healthy-" + UUID.randomUUID().toString().substring(0, 8);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        Logger relayLogger = (Logger) LoggerFactory.getLogger(OutboxRelay.class);
        relayLogger.addAppender(logs);
        OutboxEvent poison = null;
        try {
            // 2 MB: larger than the producer's max.request.size (1 MB), so Kafka's client refuses it for good
            poison = outbox.save(OutboxEvent.builder().topic("payment.refunded").eventKey(poisonKey)
                    .payload("{\"filler\":\"" + "x".repeat(2_000_000) + "\"}").createdAt(LocalDateTime.now()).build());
            outbox.save(OutboxEvent.builder().topic("payment.refunded").eventKey(healthyKey)
                    .payload("{\"orderId\":1}").createdAt(LocalDateTime.now()).build());

            assertThat(awaitRecord("payment.refunded", healthyKey, KAFKA_TIMEOUT).value()).isEqualTo("{\"orderId\":1}");
            long poisonId = poison.getId();
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("select parked_at is not null from outbox_event where id = ?", Boolean.class, poisonId)).isTrue());

            Map<String, Object> row = jdbc.queryForMap("select attempts, last_error from outbox_event where id = ?", poisonId);
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat((String) row.get("last_error")).contains("RecordTooLargeException");
            assertThat(outboxRows(healthyKey)).as("delivered rows are deleted").isZero();
            await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(jdbc.queryForObject("select attempts from outbox_event where id = ?", Integer.class, poisonId)).isEqualTo(1));
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR).hasSize(1);
            assertThat(logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).findFirst().orElseThrow().getFormattedMessage())
                    .contains("UPDATE outbox_event SET parked_at = NULL WHERE id = " + poisonId);
        } finally {
            relayLogger.detachAppender(logs);
            if (poison != null) {
                outbox.deleteById(poison.getId());
            }
        }
    }

    @Test
    void theOutboxMetricsAreExported() {
        String scrape = RestClient.create("http://localhost:" + port).get().uri("/actuator/prometheus").retrieve().body(String.class);

        assertThat(scrape).contains("outbox_events_pending", "outbox_events_parked",
                "outbox_events_published_total", "outbox_publish_failures_total");
    }
}
