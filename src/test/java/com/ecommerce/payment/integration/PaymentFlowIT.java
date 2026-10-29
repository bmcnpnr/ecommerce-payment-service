package com.ecommerce.payment.integration;

import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.repository.PaymentRepository;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * The whole service over HTTP against real Postgres (Flyway + validate) and Kafka: initiate →
 * process → refund, checking the rows written and the {@code payment.*} records that actually land
 * on the topics, in the wire format consumers parse (LocalDateTime as an int array, no type headers).
 *
 * <p>{@code /process} simulates the gateway with an 80/20 random draw, so the completed and failed
 * flows run fresh payments until the wanted outcome comes up.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentFlowIT extends AbstractIntegrationTest {

    private static final List<String> TOPICS = List.of("payment.completed", "payment.failed", "payment.refunded");
    private static final int MAX_ATTEMPTS = 60; // P(never failing) = 0.8^60 ≈ 1.5e-6
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static KafkaConsumer<String, String> consumer;
    private static final List<ConsumerRecord<String, String>> received = new ArrayList<>();

    @LocalServerPort int port;
    @Autowired PaymentRepository repository;

    private RestClient http;

    @BeforeAll
    static void startConsumer() throws Exception {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            for (String topic : TOPICS) {
                try {
                    admin.createTopics(List.of(new NewTopic(topic, 1, (short) 1))).all().get();
                } catch (ExecutionException e) {
                    if (!(e.getCause() instanceof TopicExistsException)) throw e;
                }
            }
        }
        consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "payment-flow-it-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class));
        consumer.subscribe(TOPICS);
    }

    @AfterAll
    static void stopConsumer() {
        if (consumer != null) consumer.close();
    }

    @BeforeEach
    void client() {
        http = RestClient.builder().baseUrl("http://localhost:" + port + "/api/v1/payments")
                .defaultStatusHandler(status -> true, (req, res) -> { })
                .build();
    }

    // ---- helpers ------------------------------------------------------------------------------

    private static long newOrderId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private ResponseEntity<String> post(String path, String body) {
        RestClient.RequestBodySpec req = http.post().uri(path);
        if (body != null) req.contentType(MediaType.APPLICATION_JSON).body(body);
        return req.retrieve().toEntity(String.class);
    }

    private ResponseEntity<String> get(String path) {
        return http.get().uri(path).retrieve().toEntity(String.class);
    }

    private static JsonNode json(ResponseEntity<String> r) {
        return JSON.readTree(r.getBody());
    }

    private JsonNode initiate(long orderId, String customerId, String amount) {
        ResponseEntity<String> r = post("", """
                {"orderId":%d,"customerId":"%s","amount":%s,"method":"CREDIT_CARD","cardLastFour":"4242"}"""
                .formatted(orderId, customerId, amount));
        assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(201);
        return json(r);
    }

    /** Initiates and processes fresh payments until one ends in {@code wanted}; returns that /process body. */
    private JsonNode processUntil(String wanted, String customerId) {
        for (int i = 0; i < MAX_ATTEMPTS; i++) {
            JsonNode created = initiate(newOrderId(), customerId, "49.99");
            ResponseEntity<String> r = post("/" + created.get("id").asLong() + "/process", null);
            assertThat(r.getStatusCode().value()).as(r.getBody()).isEqualTo(200);
            JsonNode processed = json(r);
            if (processed.get("status").asString().equals(wanted)) return processed;
        }
        throw new AssertionError("gateway simulation never returned " + wanted);
    }

    /** Waits for the record on {@code topic} keyed by the order id. */
    private static ConsumerRecord<String, String> awaitRecord(String topic, long orderId) {
        String key = Long.toString(orderId);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> {
            consumer.poll(Duration.ofMillis(200)).forEach(received::add);
            return find(topic, key).isPresent();
        });
        return find(topic, key).orElseThrow();
    }

    private static Optional<ConsumerRecord<String, String>> find(String topic, String key) {
        return received.stream().filter(r -> r.topic().equals(topic) && key.equals(r.key())).findFirst();
    }

    private static void assertIntArrayTimestamp(JsonNode node) {
        assertThat(node.isArray()).as("LocalDateTime on the wire is an int array: %s", node).isTrue();
        assertThat(node.size()).isBetween(5, 7);
        node.forEach(n -> assertThat(n.isInt()).isTrue());
    }

    // ---- flows --------------------------------------------------------------------------------

    @Test
    void initiate_complete_refund_persistsRowsAndPublishesEvents() {
        String customer = "cust-" + UUID.randomUUID();
        JsonNode completed = processUntil("COMPLETED", customer);
        long id = completed.get("id").asLong();
        long orderId = completed.get("orderId").asLong();
        String txn = completed.get("transactionId").asString();
        assertThat(txn).matches("TXN-[0-9A-F]{8}");

        Payment row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(row.getTransactionId()).isEqualTo(txn);
        assertThat(row.getAmount()).isEqualTo(new BigDecimal("49.9900"));
        assertThat(row.getCardLastFour()).isEqualTo("4242");
        assertThat(row.getCurrency()).isEqualTo("USD");
        assertThat(row.getUpdatedAt()).isNotNull();

        ConsumerRecord<String, String> rec = awaitRecord("payment.completed", orderId);
        assertThat(rec.headers().toArray()).as("no type-info headers").isEmpty();
        JsonNode ev = JSON.readTree(rec.value());
        assertThat(ev.get("paymentId").asLong()).isEqualTo(id);
        assertThat(ev.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(ev.get("customerId").asString()).isEqualTo(customer);
        assertThat(ev.get("amount").isNumber()).isTrue();
        assertThat(ev.get("amount").decimalValue()).isEqualByComparingTo("49.99");
        assertThat(ev.get("transactionId").asString()).isEqualTo(txn);
        assertIntArrayTimestamp(ev.get("completedAt"));

        // GET endpoints see the committed state
        assertThat(json(get("/" + id)).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(json(get("/order/" + orderId)).get("id").asLong()).isEqualTo(id);

        // a completed order cannot be paid again
        ResponseEntity<String> again = post("", """
                {"orderId":%d,"customerId":"%s","amount":1,"method":"CREDIT_CARD"}""".formatted(orderId, customer));
        assertThat(again.getStatusCode().value()).isEqualTo(409);

        // refund
        ResponseEntity<String> refund = post("/" + id + "/refund", null);
        assertThat(refund.getStatusCode().value()).as(refund.getBody()).isEqualTo(200);
        assertThat(json(refund).get("status").asString()).isEqualTo("REFUNDED");
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.REFUNDED);

        JsonNode refunded = JSON.readTree(awaitRecord("payment.refunded", orderId).value());
        assertThat(refunded.get("paymentId").asLong()).isEqualTo(id);
        assertThat(refunded.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(refunded.get("customerId").asString()).isEqualTo(customer);
        assertThat(refunded.get("amount").decimalValue()).isEqualByComparingTo("49.99");
        assertIntArrayTimestamp(refunded.get("refundedAt"));

        // state guards after refund
        assertThat(post("/" + id + "/refund", null).getStatusCode().value()).isEqualTo(400);
        assertThat(post("/" + id + "/process", null).getStatusCode().value()).isEqualTo(409);
        assertThat(find("payment.failed", Long.toString(orderId))).isEmpty();
    }

    @Test
    void initiate_fail_persistsReasonAndPublishesFailedEvent() {
        String customer = "cust-" + UUID.randomUUID();
        JsonNode failed = processUntil("FAILED", customer);
        long id = failed.get("id").asLong();
        long orderId = failed.get("orderId").asLong();
        assertThat(failed.get("failureReason").asString()).isEqualTo("Payment declined by gateway");

        Payment row = repository.findById(id).orElseThrow();
        assertThat(row.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(row.getFailureReason()).isEqualTo("Payment declined by gateway");
        assertThat(row.getTransactionId()).isNull();

        ConsumerRecord<String, String> rec = awaitRecord("payment.failed", orderId);
        assertThat(rec.headers().toArray()).isEmpty();
        JsonNode ev = JSON.readTree(rec.value());
        assertThat(ev.get("paymentId").asLong()).isEqualTo(id);
        assertThat(ev.get("orderId").asLong()).isEqualTo(orderId);
        assertThat(ev.get("customerId").asString()).isEqualTo(customer);
        assertThat(ev.get("failureReason").asString()).isEqualTo("Payment declined by gateway");
        assertIntArrayTimestamp(ev.get("failedAt"));

        // a failed payment can be neither re-processed nor refunded
        assertThat(post("/" + id + "/process", null).getStatusCode().value()).isEqualTo(409);
        assertThat(post("/" + id + "/refund", null).getStatusCode().value()).isEqualTo(400);
        assertThat(find("payment.completed", Long.toString(orderId))).isEmpty();
    }

    @Test
    void stateGuardsAndErrorsOverHttp() {
        String customer = "cust-" + UUID.randomUUID();
        JsonNode pending = initiate(newOrderId(), customer, "10");
        long id = pending.get("id").asLong();
        assertThat(pending.get("status").asString()).isEqualTo("PENDING");
        assertThat(pending.get("currency").asString()).isEqualTo("USD");

        ResponseEntity<String> refundPending = post("/" + id + "/refund", null);
        assertThat(refundPending.getStatusCode().value()).isEqualTo(400);
        assertThat(json(refundPending).get("message").asString()).contains("PENDING");
        assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(PaymentStatus.PENDING);

        assertThat(post("/" + id + "/process", null).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> secondProcess = post("/" + id + "/process", null);
        assertThat(secondProcess.getStatusCode().value()).isEqualTo(409);

        ResponseEntity<String> missing = get("/987654321");
        assertThat(missing.getStatusCode().value()).isEqualTo(404);
        assertThat(missing.getHeaders().getFirst("X-Correlation-Id")).isNotBlank();
        assertThat(json(missing).get("correlationId").asString())
                .isEqualTo(missing.getHeaders().getFirst("X-Correlation-Id"));
        assertThat(post("/987654321/process", null).getStatusCode().value()).isEqualTo(404);
        assertThat(post("/987654321/refund", null).getStatusCode().value()).isEqualTo(404);

        ResponseEntity<String> invalid = post("", """
                {"orderId":1,"customerId":"c","amount":0,"method":"CREDIT_CARD"}""");
        assertThat(invalid.getStatusCode().value()).isEqualTo(400);
        assertThat(json(invalid).get("fieldErrors").get(0).get("field").asString()).isEqualTo("amount");

        JsonNode list = json(get("/customer/" + customer));
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).get("id").asLong()).isEqualTo(id);
    }

    @Test
    void initiate_unknownMethod_returns400() {
        ResponseEntity<String> r = post("", """
                {"orderId":%d,"customerId":"c","amount":5,"method":"BITCOIN"}""".formatted(newOrderId()));
        assertThat(r.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void initiateTwiceForSameOrder_keepsOrderLookupWorking() {
        long orderId = newOrderId();
        initiate(orderId, "cust-dup", "5");
        ResponseEntity<String> second = post("", """
                {"orderId":%d,"customerId":"cust-dup","amount":5,"method":"CREDIT_CARD"}""".formatted(orderId));
        assertThat(second.getStatusCode().value()).isIn(200, 201, 409);

        assertThat(get("/order/" + orderId).getStatusCode().value()).isEqualTo(200);
        ResponseEntity<String> third = post("", """
                {"orderId":%d,"customerId":"cust-dup","amount":5,"method":"CREDIT_CARD"}""".formatted(orderId));
        assertThat(third.getStatusCode().is5xxServerError()).as(third.getBody()).isFalse();
    }
}
