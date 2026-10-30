package com.ecommerce.payment.integration;

import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentMethod;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.repository.PaymentRepository;
import com.sun.net.httpserver.HttpServer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Security tests against the whole service: real Postgres (Flyway + validate) and Kafka.
 * {@code alice} is a CUSTOMER, {@code bob} the victim; identity is sent the way the gateway
 * forwards it ({@code X-Username}, {@code X-User-Id}, {@code X-User-Role}).
 *
 * <p>Tests assert the secure behaviour; those the current code fails are {@code @Disabled} with the
 * finding id, after being run enabled to confirm they fail for that reason alone.
 */
@Tag("security")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PaymentSecurityIT extends AbstractIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort int port;
    @Autowired PaymentRepository repository;
    @Autowired JdbcTemplate jdbc;

    private RestClient http;

    private static final java.util.function.Consumer<org.springframework.http.HttpHeaders> ROOT = h -> {
        h.set("X-Username", "root");
        h.set("X-User-Id", "1");
        h.set("X-User-Role", "ADMIN");
    };

    @BeforeEach
    void client() {
        http = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    // ---- helpers -----------------------------------------------------------------------------------------

    private static long newOrderId() {
        return ThreadLocalRandom.current().nextLong(1_000_000_000L, Long.MAX_VALUE);
    }

    private static String uniq(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ResponseEntity<String> as(String user, String role, HttpMethod method, String path, String jsonBody) {
        var spec = http.method(method).uri(path).headers(h -> {
            if (user != null) {
                h.add("X-Username", user);
                h.add("X-User-Id", Integer.toString(Math.abs(user.hashCode()) % 100000));
            }
            if (role != null) {
                h.add("X-User-Role", role);
            }
        });
        if (jsonBody != null) {
            spec = spec.contentType(MediaType.APPLICATION_JSON).body(jsonBody);
        }
        return spec.retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
    }

    private ResponseEntity<String> anon(HttpMethod method, String path, String jsonBody) {
        return as(null, null, method, path, jsonBody);
    }

    private ResponseEntity<String> admin(HttpMethod method, String path, String jsonBody) {
        return as("root", "ADMIN", method, path, jsonBody);
    }

    private ResponseEntity<String> asCustomer(String user, HttpMethod method, String path, String jsonBody) {
        return as(user, "CUSTOMER", method, path, jsonBody);
    }

    private static String initiateJson(long orderId, String customerId, String amount) {
        return """
                {"orderId":%d,"customerId":"%s","amount":%s,"method":"CREDIT_CARD","cardLastFour":"4242"}"""
                .formatted(orderId, customerId, amount);
    }

    private Payment seed(String customerId, PaymentStatus status, String amount) {
        Payment payment = Payment.builder().orderId(newOrderId()).customerId(customerId).amount(new BigDecimal(amount))
                .currency("USD").method(PaymentMethod.CREDIT_CARD).status(status).cardLastFour("4242").build();
        if (status == PaymentStatus.COMPLETED || status == PaymentStatus.REFUNDED) {
            payment.setTransactionId("TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        }
        return repository.saveAndFlush(payment);
    }

    private String statusOf(long paymentId) {
        return jdbc.queryForObject("select status from payments where id = ?", String.class, paymentId);
    }

    private long rowsForOrder(long orderId) {
        return jdbc.queryForObject("select count(*) from payments where order_id = ?", Long.class, orderId);
    }

    private static void assertDenied(ResponseEntity<String> response, String... mustNotContain) {
        assertThat(response.getStatusCode().value()).as("body: %s", response.getBody()).isIn(401, 403, 404);
        for (String secret : mustNotContain) {
            assertThat(response.getBody()).doesNotContain(secret);
        }
    }

    /** Records with {@code key} on {@code topics}, read from the beginning over {@code window}. */
    private static List<ConsumerRecord<String, String>> records(List<String> topics, String key, Duration window) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "sec-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(topics);
            Instant deadline = Instant.now().plus(window);
            while (Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                }
            }
        }
        return found;
    }

    private static final List<String> ALL_PAYMENT_TOPICS = List.of("payment.completed", "payment.failed", "payment.refunded");

    // ==== Authorization / IDOR (S-1) ===============================================================================

    @Test
    void owner_canReadOwnPayment_andAdmin_canReadAnyPayment() {
        String alice = uniq("alice");
        Payment payment = seed(alice, PaymentStatus.COMPLETED, "25.00");

        ResponseEntity<String> own = asCustomer(alice, HttpMethod.GET, "/api/v1/payments/" + payment.getId(), null);
        ResponseEntity<String> admin = as("root", "ADMIN", HttpMethod.GET, "/api/v1/payments/customer/" + alice, null);

        assertThat(own.getStatusCode().value()).isEqualTo(200);
        assertThat(JSON.readTree(own.getBody()).get("customerId").asString()).isEqualTo(alice);
        assertThat(JSON.readTree(admin.getBody())).hasSize(1);
    }

    @Test
    void admin_canRefundACompletedPayment() {
        Payment payment = seed(uniq("carol"), PaymentStatus.COMPLETED, "25.00");

        ResponseEntity<String> response = as("root", "ADMIN", HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/refund", null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(statusOf(payment.getId())).isEqualTo("REFUNDED");
    }

    @Test
    void customer_cannotReadAnotherUsersPaymentById() {
        String bob = uniq("bob");
        Payment payment = seed(bob, PaymentStatus.COMPLETED, "25.00");

        assertDenied(asCustomer(uniq("alice"), HttpMethod.GET, "/api/v1/payments/" + payment.getId(), null), bob, "4242");
    }

    @Test
    void customer_cannotReadAnotherUsersPaymentByOrder() {
        String bob = uniq("bob");
        Payment payment = seed(bob, PaymentStatus.COMPLETED, "25.00");

        assertDenied(asCustomer(uniq("alice"), HttpMethod.GET, "/api/v1/payments/order/" + payment.getOrderId(), null), bob, "4242");
    }

    @Test
    void customer_cannotListAnotherCustomersPayments() {
        String bob = uniq("bob");
        seed(bob, PaymentStatus.COMPLETED, "25.00");

        assertDenied(asCustomer(uniq("alice"), HttpMethod.GET, "/api/v1/payments/customer/" + bob, null), bob, "4242");
    }

    @Test
    void customer_cannotInitiateAPaymentForAnotherCustomer() {
        String bob = uniq("bob");
        long orderId = newOrderId();

        ResponseEntity<String> response = asCustomer(uniq("alice"), HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, bob, "25.00"));

        assertThat(response.getStatusCode().value()).isIn(400, 403);
        assertThat(rowsForOrder(orderId)).isZero();
    }

    @Test
    void customer_cannotProcessAnotherUsersPayment() {
        Payment payment = seed(uniq("bob"), PaymentStatus.PENDING, "25.00");

        ResponseEntity<String> response = asCustomer(uniq("alice"), HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/process", null);

        assertDenied(response);
        assertThat(statusOf(payment.getId())).isEqualTo("PENDING");
        assertThat(records(ALL_PAYMENT_TOPICS, Long.toString(payment.getOrderId()), Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void customer_cannotRefund() {
        String alice = uniq("alice");
        Payment payment = seed(alice, PaymentStatus.COMPLETED, "25.00");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/refund", null);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
        assertThat(statusOf(payment.getId())).isEqualTo("COMPLETED");
        assertThat(records(List.of("payment.refunded"), Long.toString(payment.getOrderId()), Duration.ofSeconds(3))).isEmpty();
    }

    @Test
    void requestsWithoutIdentityHeaders_areRejected() {
        String bob = uniq("bob");
        Payment payment = seed(bob, PaymentStatus.PENDING, "25.00");

        assertThat(anon(HttpMethod.GET, "/api/v1/payments/" + payment.getId(), null).getStatusCode().value()).isEqualTo(401);
        assertThat(anon(HttpMethod.GET, "/api/v1/payments/customer/" + bob, null).getStatusCode().value()).isEqualTo(401);
        assertThat(anon(HttpMethod.POST, "/api/v1/payments", initiateJson(newOrderId(), bob, "25.00")).getStatusCode().value()).isEqualTo(401);
        assertThat(anon(HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/refund", null).getStatusCode().value()).isEqualTo(401);
    }

    // ==== Amount must be the order total (I-1) ===========================================================================

    @Test
    void initiate_withAmountDifferentFromTheOrderTotal_isRejected() {
        String alice = uniq("alice");
        long orderId = newOrderId();
        registerOrder(orderId, alice, "1000.00", "CONFIRMED");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, alice, "0.01"));

        assertThat(response.getStatusCode().is4xxClientError()).as(response.getBody()).isTrue();
        assertThat(rowsForOrder(orderId)).isZero();
    }

    @Test
    void initiate_forAnOrderThatIsNotConfirmed_isRejected() {
        String alice = uniq("alice");
        long orderId = newOrderId();
        registerOrder(orderId, alice, "1000.00", "PENDING");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, alice, "1000.00"));

        assertThat(response.getStatusCode().is4xxClientError()).as(response.getBody()).isTrue();
        assertThat(rowsForOrder(orderId)).isZero();
    }

    @Test
    void initiate_forAnotherUsersOrder_isRejected() {
        String bob = uniq("bob");
        String alice = uniq("alice");
        long orderId = newOrderId();
        registerOrder(orderId, bob, "1000.00", "CONFIRMED");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, alice, "1000.00"));

        assertThat(response.getStatusCode().is4xxClientError()).as(response.getBody()).isTrue();
        assertThat(rowsForOrder(orderId)).isZero();
    }

    @Test
    void initiate_forAConfirmedOrder_withTheExactTotal_isAccepted() {
        String alice = uniq("alice");
        long orderId = newOrderId();
        registerOrder(orderId, alice, "1000.0000", "CONFIRMED");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, alice, "1000.00"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(201);
        assertThat(rowsForOrder(orderId)).isEqualTo(1);
    }

    @Test
    void initiate_forAnUnknownOrder_is404() {
        String alice = uniq("alice");
        long orderId = newOrderId();

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, alice, "10.00"));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(rowsForOrder(orderId)).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {500, 503, 502, 401})
    void initiate_whenOrderServiceFails_is503_andNothingIsStored(int downstreamStatus) {
        String alice = uniq("alice");
        long orderId = newOrderId();
        registerOrder(orderId, alice, "10.00", "CONFIRMED");
        fakeOrderServiceForcedStatus = downstreamStatus;
        try {
            ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, alice, "10.00"));

            assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(503);
            assertThat(response.getBody()).doesNotContain("127.0.0.1", "order-service", "feign", "Feign");
            assertThat(rowsForOrder(orderId)).isZero();
        } finally {
            fakeOrderServiceForcedStatus = 0;
        }
    }

    @Test
    void initiate_asAdmin_forACustomersOrder_isAccepted_butTheOrderMustStillBelongToThatCustomer() {
        String alice = uniq("alice");
        long own = newOrderId();
        long other = newOrderId();
        registerOrder(own, alice, "10.00", "CONFIRMED");
        registerOrder(other, uniq("bob"), "10.00", "CONFIRMED");

        assertThat(admin(HttpMethod.POST, "/api/v1/payments", initiateJson(own, alice, "10.00")).getStatusCode().value()).isEqualTo(201);
        assertThat(admin(HttpMethod.POST, "/api/v1/payments", initiateJson(other, alice, "10.00")).getStatusCode().value()).isEqualTo(403);
        assertThat(rowsForOrder(other)).isZero();
    }

    @Test
    void customer_canProcessOwnPayment_andReadItByOrder() {
        String alice = uniq("alice");
        Payment payment = seed(alice, PaymentStatus.PENDING, "25.00");

        ResponseEntity<String> processed = asCustomer(alice, HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/process", null);
        ResponseEntity<String> byOrder = asCustomer(alice, HttpMethod.GET, "/api/v1/payments/order/" + payment.getOrderId(), null);

        assertThat(processed.getStatusCode().value()).isEqualTo(200);
        assertThat(byOrder.getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void authorizationFailures_haveGenericBodies_andRoleComparisonIsExact() {
        String bob = uniq("bob");
        Payment payment = seed(bob, PaymentStatus.COMPLETED, "25.00");

        ResponseEntity<String> anonymous = anon(HttpMethod.GET, "/api/v1/payments/" + payment.getId(), null);
        ResponseEntity<String> forbidden = asCustomer(uniq("alice"), HttpMethod.GET, "/api/v1/payments/" + payment.getId(), null);

        assertThat(JSON.readTree(anonymous.getBody()).get("message").asString()).isEqualTo("Authentication required");
        assertThat(JSON.readTree(forbidden.getBody()).get("message").asString()).isEqualTo("Access denied");
        for (String role : new String[]{"admin", "Admin", "ROLE_ADMIN", "ADMINS"}) {
            assertThat(as("root", role, HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/refund", null).getStatusCode().value()).as(role).isEqualTo(403);
        }
        assertThat(statusOf(payment.getId())).isEqualTo("COMPLETED");
    }

    @Test
    void completedPayment_withAnInconsistentConfirmedOrder_stillCannotBeRewritten() {
        String alice = uniq("alice");
        Payment paid = seed(alice, PaymentStatus.COMPLETED, "1000.00");
        registerOrder(paid.getOrderId(), alice, "1000.00", "CONFIRMED");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(paid.getOrderId(), alice, "1000.00"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(statusOf(paid.getId())).isEqualTo("COMPLETED");
    }

    /**
     * A declined payment ends the order (order-service cancels it on payment.failed). Until that event has been
     * consumed the order still reads CONFIRMED, so the order check passes: the payment itself has to refuse.
     */
    @Test
    void initiate_retryAfterFailure_isRefused_409_theRowStaysFailed_andNothingIsChargedOrPublished() {
        String alice = uniq("alice");
        Payment failed = seed(alice, PaymentStatus.FAILED, "10.00");
        jdbc.update("update payments set failure_reason = 'Payment declined by gateway' where id = ?", failed.getId());
        registerOrder(failed.getOrderId(), alice, "10.00", "CONFIRMED");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(failed.getOrderId(), alice, "10.00"));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(409);
        assertThat(JSON.readTree(response.getBody()).get("message").asString())
                .isEqualTo("The payment for this order was declined and the order is cancelled. Place a new order to try again.");
        assertThat(statusOf(failed.getId())).isEqualTo("FAILED");
        assertThat(rowsForOrder(failed.getOrderId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select failure_reason from payments where id = ?", String.class, failed.getId()))
                .isEqualTo("Payment declined by gateway");
        // the gateway cannot be reached through /process either
        ResponseEntity<String> process = asCustomer(alice, HttpMethod.POST, "/api/v1/payments/" + failed.getId() + "/process", null);
        assertThat(process.getStatusCode().value()).isEqualTo(409);
        assertThat(statusOf(failed.getId())).isEqualTo("FAILED");
        assertThat(records(ALL_PAYMENT_TOPICS, Long.toString(failed.getOrderId()), Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void initiate_retryAfterFailure_byAnotherCustomer_learnsNothingAboutTheDecline() {
        String alice = uniq("alice");
        String bob = uniq("bob");
        Payment failed = seed(alice, PaymentStatus.FAILED, "10.00");
        registerOrder(failed.getOrderId(), bob, "10.00", "CONFIRMED"); // even if the order data were bent to fit bob

        ResponseEntity<String> response = asCustomer(bob, HttpMethod.POST, "/api/v1/payments", initiateJson(failed.getOrderId(), bob, "10.00"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody()).doesNotContain("declined").doesNotContain("FAILED");
        assertThat(statusOf(failed.getId())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select customer_id from payments where id = ?", String.class, failed.getId())).isEqualTo(alice);
    }

    @Test
    void initiate_byAnotherCustomer_ofARegisteredOrder_cannotHijackAPendingPayment() {
        String alice = uniq("alice");
        String bob = uniq("bob");
        Payment pending = seed(alice, PaymentStatus.PENDING, "1000.00");
        registerOrder(pending.getOrderId(), bob, "0.01", "CONFIRMED"); // even if the order data were bent to fit bob

        ResponseEntity<String> response = asCustomer(bob, HttpMethod.POST, "/api/v1/payments", initiateJson(pending.getOrderId(), bob, "0.01"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        Map<String, Object> row = jdbc.queryForMap("select customer_id, amount from payments where id = ?", pending.getId());
        assertThat(row.get("customer_id")).isEqualTo(alice);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1000.00");
    }

    @Test
    void apiResponses_carryNosniffAndNoStore_andBadCorrelationIdsAreReplaced() {
        ResponseEntity<String> response = http.get().uri("/api/v1/payments/1").header("X-Correlation-Id", "bad id <script>")
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeaders().getFirst("Cache-Control")).isEqualTo("no-store");
        assertThat(response.getHeaders().getFirst("X-Correlation-Id")).matches("[0-9a-f-]{36}");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.011", "1.005", "1000000000000000.00", "99999999999999999999.99", "1e400", "0", "-1"})
    void initiate_invalidAmounts_are400_andStoreNothing(String amount) {
        String alice = uniq("alice");
        long orderId = newOrderId();

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, alice, amount));

        assertThat(response.getStatusCode().value()).as(response.getBody()).isEqualTo(400);
        assertThat(rowsForOrder(orderId)).isZero();
    }

    @Test
    void initiate_overlongOrNulCustomerId_is400_notA500() {
        for (String bad : new String[]{"a".repeat(300), "a\\u0000b"}) {
            ResponseEntity<String> response = admin(HttpMethod.POST, "/api/v1/payments", initiateJson(newOrderId(), bad, "10.00"));
            assertThat(response.getStatusCode().value()).as("%d chars: %s", bad.length(), response.getBody()).isEqualTo(400);
        }
    }

    // ==== State machine and duplicates======================================================================================

    @Test
    void secondPaymentForAPaidOrder_isRejected_andCannotRewriteTheFirst() {
        String alice = uniq("alice");
        Payment paid = seed(alice, PaymentStatus.COMPLETED, "1000.00");
        registerOrder(paid.getOrderId(), alice, "1000.00", "PAID");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(paid.getOrderId(), alice, "0.01"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(rowsForOrder(paid.getOrderId())).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select amount, status, transaction_id from payments where id = ?", paid.getId());
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1000.00");
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("transaction_id")).isEqualTo(paid.getTransactionId());
    }

    @Test
    void initiate_byAnotherCustomer_cannotHijackAPendingPayment() {
        String alice = uniq("alice");
        Payment pending = seed(alice, PaymentStatus.PENDING, "1000.00");

        ResponseEntity<String> response = asCustomer(uniq("bob"), HttpMethod.POST, "/api/v1/payments", initiateJson(pending.getOrderId(), "bob", "0.01"));

        assertThat(response.getStatusCode().is4xxClientError()).as(response.getBody()).isTrue();
        Map<String, Object> row = jdbc.queryForMap("select customer_id, amount from payments where id = ?", pending.getId());
        assertThat(row.get("customer_id")).isEqualTo(alice);
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1000.00");
    }

    @Test
    void initiate_afterARefund_isRejected() {
        String alice = uniq("alice");
        Payment refunded = seed(alice, PaymentStatus.REFUNDED, "1000.00");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(refunded.getOrderId(), alice, "1000.00"));

        assertThat(response.getStatusCode().is4xxClientError()).as(response.getBody()).isTrue();
        assertThat(statusOf(refunded.getId())).isEqualTo("REFUNDED");
    }

    @Test
    void refund_ofAPaymentThatWasNeverCollected_isRejected_withNoEvent() {
        Payment pending = seed(uniq("carol"), PaymentStatus.PENDING, "25.00");
        Payment failed = seed(uniq("carol"), PaymentStatus.FAILED, "25.00");

        for (Payment payment : List.of(pending, failed)) {
            ResponseEntity<String> response = as("root", "ADMIN", HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/refund", null);
            assertThat(response.getStatusCode().value()).isEqualTo(400);
            assertThat(records(List.of("payment.refunded"), Long.toString(payment.getOrderId()), Duration.ofSeconds(2))).isEmpty();
        }
        assertThat(statusOf(pending.getId())).isEqualTo("PENDING");
        assertThat(statusOf(failed.getId())).isEqualTo("FAILED");
    }

    @Test
    void refund_twice_isRejected_andRefundsExactlyTheAmountPaid_evenWhenTheCallerAsksForMore() {
        Payment payment = seed(uniq("carol"), PaymentStatus.COMPLETED, "25.00");
        String path = "/api/v1/payments/" + payment.getId() + "/refund";

        ResponseEntity<String> first = as("root", "ADMIN", HttpMethod.POST, path + "?amount=99999.00", "{\"amount\":99999.00}");
        ResponseEntity<String> second = as("root", "ADMIN", HttpMethod.POST, path + "?amount=99999.00", "{\"amount\":99999.00}");

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(second.getStatusCode().value()).isEqualTo(400);
        List<ConsumerRecord<String, String>> events = records(List.of("payment.refunded"), Long.toString(payment.getOrderId()), Duration.ofSeconds(5));
        assertThat(events).hasSize(1);
        assertThat(JSON.readTree(events.get(0).value()).get("amount").decimalValue()).isEqualByComparingTo("25.00");
        assertThat(jdbc.queryForObject("select amount from payments where id = ?", BigDecimal.class, payment.getId())).isEqualByComparingTo("25.00");
    }

    @Test
    void process_ofACompletedOrRefundedPayment_isRejected_withNoNewEvent() {
        Payment completed = seed(uniq("carol"), PaymentStatus.COMPLETED, "25.00");
        Payment refunded = seed(uniq("carol"), PaymentStatus.REFUNDED, "25.00");

        for (Payment payment : List.of(completed, refunded)) {
            ResponseEntity<String> response = admin(HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/process", null);
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(records(ALL_PAYMENT_TOPICS, Long.toString(payment.getOrderId()), Duration.ofSeconds(2))).isEmpty();
        }
        assertThat(jdbc.queryForObject("select transaction_id from payments where id = ?", String.class, completed.getId())).isEqualTo(completed.getTransactionId());
    }

    @Test
    void concurrentProcess_ofOnePayment_publishesExactlyOneOutcome() throws Exception {
        Payment payment = seed(uniq("carol"), PaymentStatus.PENDING, "25.00");
        int threads = 12;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return admin(HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/process", null).getStatusCode().value();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 200).count()).as("statuses %s", statuses).isEqualTo(1);
        assertThat(records(List.of("payment.completed", "payment.failed"), Long.toString(payment.getOrderId()), Duration.ofSeconds(5))).hasSize(1);
    }

    @Test
    void concurrentInitiate_forOneOrder_leavesOneRow_andNeverAServerError() throws Exception {
        String alice = uniq("alice");
        long orderId = newOrderId();
        registerOrder(orderId, alice, "10.00", "CONFIRMED");
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            results.add(pool.submit(() -> {
                go.await();
                return asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, alice, "10.00")).getStatusCode().value();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(rowsForOrder(orderId)).isEqualTo(1);
        assertThat(statuses).as("no request may fail with a server error").allMatch(s -> s < 500);
    }

    // ==== Request tampering ==================================================================================================

    @Test
    void initiate_serverControlledFieldsInTheBody_areIgnored() {
        String alice = uniq("alice");
        long orderId = newOrderId();
        registerOrder(orderId, alice, "10.00", "CONFIRMED");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", """
                {"orderId":%d,"customerId":"%s","amount":10.00,"method":"CREDIT_CARD","cardLastFour":"4242",
                 "id":424242,"status":"COMPLETED","transactionId":"TXN-FORGED","failureReason":"x",
                 "createdAt":"1999-01-01T00:00:00","updatedAt":"1999-01-01T00:00:00"}""".formatted(orderId, alice));

        assertThat(response.getStatusCode().is2xxSuccessful()).as(response.getBody()).isTrue();
        JsonNode created = JSON.readTree(response.getBody());
        assertThat(created.get("id").asLong()).isNotEqualTo(424242L);
        assertThat(created.get("status").asString()).isEqualTo("PENDING");
        assertThat(created.get("transactionId").isNull()).isTrue();
        Map<String, Object> row = jdbc.queryForMap("select status, transaction_id, failure_reason, created_at from payments where order_id = ?", orderId);
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(row.get("transaction_id")).isNull();
        assertThat(row.get("failure_reason")).isNull();
        assertThat(row.get("created_at").toString()).doesNotStartWith("1999");
        assertThat(records(ALL_PAYMENT_TOPICS, Long.toString(orderId), Duration.ofSeconds(2))).isEmpty();
    }

    @Test
    void initiate_withAFullCardNumber_isRejected_andNothingIsStoredOrEchoed() {
        String pan = "4111111111111111";
        String alice = uniq("alice");
        long orderId = newOrderId();

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", """
                {"orderId":%d,"customerId":"%s","amount":10.00,"method":"CREDIT_CARD","cardLastFour":"%s"}""".formatted(orderId, alice, pan));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody()).doesNotContain(pan);
        assertThat(rowsForOrder(orderId)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from payments where card_last_four like '%4111%' or length(card_last_four) > 4", Long.class)).isZero();
    }

    @Test
    void initiate_xssInCardField_isRefused_andNothingIsStored() {
        long orderId = newOrderId();

        ResponseEntity<String> response = asCustomer("alice", HttpMethod.POST, "/api/v1/payments", """
                {"orderId":%d,"customerId":"alice","amount":10.00,"method":"CREDIT_CARD","cardLastFour":"<script>"}""".formatted(orderId));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(rowsForOrder(orderId)).isZero();
    }

    // ==== Injection & data ========================================================================================================

    private static final String[] SQLI = {
            "' OR '1'='1", "' OR 1=1 --", "bob' --", "'; DROP TABLE payments; --", "\" OR \"\"=\"", "%", "_ob",
            "' UNION SELECT NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL,NULL --"
    };

    @Test
    void sqlInjectionInCustomerPath_returnsNothing_andLeavesTheTableIntact() {
        String bob = uniq("bob");
        seed(bob, PaymentStatus.COMPLETED, "25.00");
        long before = jdbc.queryForObject("select count(*) from payments", Long.class);

        for (String payload : SQLI) {
            ResponseEntity<String> response = http.get().uri("/api/v1/payments/customer/{c}", payload).headers(ROOT)
                    .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
            assertThat(response.getStatusCode().value()).as(payload).isEqualTo(200);
            assertThat(JSON.readTree(response.getBody())).as("result for %s", payload).isEmpty();
        }

        assertThat(jdbc.queryForObject("select count(*) from payments", Long.class)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select to_regclass('public.payments') is not null", Boolean.class)).isTrue();
    }

    @Test
    void sqlInjectionAndXssInCustomerId_areStoredAsPlainData_andReturnedAsJson() {
        List<String> payloads = new ArrayList<>(List.of(SQLI));
        payloads.add("<script>alert(1)</script>");
        payloads.add("\"><img src=x onerror=alert(document.cookie)>");
        payloads.add("</script><svg/onload=alert(1)>");
        long before = jdbc.queryForObject("select count(*) from payments", Long.class);

        for (String raw : payloads) {
            String payload = raw + " " + uniq("u");
            long orderId = newOrderId();
            registerOrder(orderId, payload, "10.00", "CONFIRMED");
            String body = JSON.writeValueAsString(Map.of("orderId", orderId, "customerId", payload, "amount", 10.00,
                    "method", "CREDIT_CARD", "cardLastFour", "4242"));
            ResponseEntity<String> response = as(payload, "CUSTOMER", HttpMethod.POST, "/api/v1/payments", body);

            assertThat(response.getStatusCode().value()).as(payload).isEqualTo(201);
            assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
            assertThat(JSON.readTree(response.getBody()).get("customerId").asString()).isEqualTo(payload);
            assertThat(jdbc.queryForObject("select customer_id from payments where order_id = ?", String.class, orderId)).isEqualTo(payload);
        }

        assertThat(jdbc.queryForObject("select count(*) from payments", Long.class)).isEqualTo(before + payloads.size());
        assertThat(jdbc.queryForObject("select to_regclass('public.payments') is not null", Boolean.class)).isTrue();
    }

    @Test
    void injectionInMethodAndInPathIds_isA400_andStoresNothing() {
        long orderId = newOrderId();
        registerOrder(orderId, "alice", "10.00", "CONFIRMED");

        ResponseEntity<String> badMethod = http.post().uri("/api/v1/payments").headers(h -> { h.set("X-Username", "alice"); h.set("X-User-Role", "CUSTOMER"); })
                .contentType(MediaType.APPLICATION_JSON).body("""
                {"orderId":%d,"customerId":"alice","amount":10.00,"method":"CREDIT_CARD'; DROP TABLE payments;--"}""".formatted(orderId))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);
        ResponseEntity<String> badId = http.get().uri("/api/v1/payments/{id}", "1 OR 1=1")
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(badMethod.getStatusCode().value()).isEqualTo(400);
        assertThat(badId.getStatusCode().value()).isEqualTo(400);
        assertThat(rowsForOrder(orderId)).isZero();
        assertThat(jdbc.queryForObject("select to_regclass('public.payments') is not null", Boolean.class)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/payments/-1", "/api/v1/payments/0", "/api/v1/payments/99999999999999999999", "/api/v1/payments/%00",
            "/api/v1/payments/%27", "/api/v1/payments/customer/%00", "/api/v1/payments/order/1%20OR%201=1", "/api/v1/payments/1%2F..%2Factuator"})
    void hostilePathProbes_neverProduceAServerError(String path) {
        ResponseEntity<String> response = http.get().uri(URI.create("http://localhost:" + port + path))
                .retrieve().onStatus(s -> true, (rq, rs) -> { }).toEntity(String.class);

        assertThat(response.getStatusCode().value()).as("%s -> %s", path, response.getBody()).isLessThan(500);
    }

    @Test
    void errorResponses_areJson_withoutStackTracesOrSql() {
        Payment completed = seed(uniq("carol"), PaymentStatus.COMPLETED, "25.00");
        List<ResponseEntity<String>> errors = List.of(
                admin(HttpMethod.GET, "/api/v1/payments/987654321", null),
                admin(HttpMethod.GET, "/api/v1/payments/order/987654321", null),
                admin(HttpMethod.POST, "/api/v1/payments/" + completed.getId() + "/process", null),
                admin(HttpMethod.POST, "/api/v1/payments/987654321/refund", null),
                admin(HttpMethod.POST, "/api/v1/payments", initiateJson(newOrderId(), "alice", "-1")),
                admin(HttpMethod.POST, "/api/v1/payments", "{not json"),
                admin(HttpMethod.PUT, "/api/v1/payments/1", null),
                admin(HttpMethod.GET, "/api/v1/payments/nope/nope", null));

        for (ResponseEntity<String> error : errors) {
            assertThat(error.getStatusCode().is4xxClientError()).as(error.getBody()).isTrue();
            assertThat(error.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
            assertThat(error.getBody()).doesNotContain("\tat ", "org.springframework", "org.hibernate", "org.postgresql", "select ", "SQLState", "constraint");
        }
    }
}
