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
import org.junit.jupiter.api.Disabled;
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

    /** Stand-in for order-service, for the tests that need to know what an order is really worth. */
    private static final Map<Long, String> FAKE_ORDERS = new ConcurrentHashMap<>();
    private static HttpServer fakeOrderService;

    @DynamicPropertySource
    static void orderServiceProperties(DynamicPropertyRegistry registry) throws Exception {
        if (fakeOrderService == null) {
            fakeOrderService = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            fakeOrderService.createContext("/api/v1/orders/", exchange -> {
                String id = exchange.getRequestURI().getPath().substring("/api/v1/orders/".length());
                String json = id.matches("\\d+") ? FAKE_ORDERS.get(Long.parseLong(id)) : null;
                byte[] bytes = (json == null ? "{}" : json).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(json == null ? 404 : 200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            fakeOrderService.start();
        }
        registry.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri",
                () -> "http://127.0.0.1:" + fakeOrderService.getAddress().getPort());
    }

    @AfterAll
    static void stopFakeOrderService() {
        if (fakeOrderService != null) {
            fakeOrderService.stop(0);
        }
    }

    @LocalServerPort int port;
    @Autowired PaymentRepository repository;
    @Autowired JdbcTemplate jdbc;

    private RestClient http;

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

    @Disabled("BUG S-1: GET /payments/{id} has no ownership check, so alice reads bob's payment")
    @Test
    void customer_cannotReadAnotherUsersPaymentById() {
        String bob = uniq("bob");
        Payment payment = seed(bob, PaymentStatus.COMPLETED, "25.00");

        assertDenied(asCustomer(uniq("alice"), HttpMethod.GET, "/api/v1/payments/" + payment.getId(), null), bob, "4242");
    }

    @Disabled("BUG S-1: GET /payments/order/{orderId} has no ownership check, so alice reads the payment of bob's order")
    @Test
    void customer_cannotReadAnotherUsersPaymentByOrder() {
        String bob = uniq("bob");
        Payment payment = seed(bob, PaymentStatus.COMPLETED, "25.00");

        assertDenied(asCustomer(uniq("alice"), HttpMethod.GET, "/api/v1/payments/order/" + payment.getOrderId(), null), bob, "4242");
    }

    @Disabled("BUG S-1: GET /payments/customer/{customerId} trusts the path, so alice lists bob's payments")
    @Test
    void customer_cannotListAnotherCustomersPayments() {
        String bob = uniq("bob");
        seed(bob, PaymentStatus.COMPLETED, "25.00");

        assertDenied(asCustomer(uniq("alice"), HttpMethod.GET, "/api/v1/payments/customer/" + bob, null), bob, "4242");
    }

    @Disabled("BUG S-1: POST /payments takes customerId from the body, so alice can open a payment in bob's name")
    @Test
    void customer_cannotInitiateAPaymentForAnotherCustomer() {
        String bob = uniq("bob");
        long orderId = newOrderId();

        ResponseEntity<String> response = asCustomer(uniq("alice"), HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, bob, "25.00"));

        assertThat(response.getStatusCode().value()).isIn(400, 403);
        assertThat(rowsForOrder(orderId)).isZero();
    }

    @Disabled("BUG S-1: POST /payments/{id}/process has no ownership check, so alice runs (and can fail) bob's pending payment")
    @Test
    void customer_cannotProcessAnotherUsersPayment() {
        Payment payment = seed(uniq("bob"), PaymentStatus.PENDING, "25.00");

        ResponseEntity<String> response = asCustomer(uniq("alice"), HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/process", null);

        assertDenied(response);
        assertThat(statusOf(payment.getId())).isEqualTo("PENDING");
        assertThat(records(ALL_PAYMENT_TOPICS, Long.toString(payment.getOrderId()), Duration.ofSeconds(3))).isEmpty();
    }

    @Disabled("BUG S-1: POST /payments/{id}/refund is documented as admin-only but a CUSTOMER can refund (even their own) payment")
    @Test
    void customer_cannotRefund() {
        String alice = uniq("alice");
        Payment payment = seed(alice, PaymentStatus.COMPLETED, "25.00");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/refund", null);

        assertThat(response.getStatusCode().value()).isIn(401, 403);
        assertThat(statusOf(payment.getId())).isEqualTo("COMPLETED");
        assertThat(records(List.of("payment.refunded"), Long.toString(payment.getOrderId()), Duration.ofSeconds(3))).isEmpty();
    }

    @Disabled("BUG S-1: without X-Username/X-User-Role (a request that bypassed the gateway) every endpoint still answers")
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

    private static void registerOrder(long orderId, String customerId, String total, String status) {
        FAKE_ORDERS.put(orderId, """
                {"orderId":%d,"customerId":"%s","totalAmount":%s,"status":"%s","orderItems":[]}""".formatted(orderId, customerId, total, status));
    }

    @Disabled("BUG I-1: the amount is taken from the request; payment-service never asks order-service, so 0.01 pays a 1000.00 order (needs order-service reachable as 'order-service' via discovery, as the fix would)")
    @Test
    void initiate_withAmountDifferentFromTheOrderTotal_isRejected() {
        String alice = uniq("alice");
        long orderId = newOrderId();
        registerOrder(orderId, alice, "1000.00", "CONFIRMED");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, alice, "0.01"));

        assertThat(response.getStatusCode().is4xxClientError()).as(response.getBody()).isTrue();
        assertThat(rowsForOrder(orderId)).isZero();
    }

    @Disabled("BUG I-1: a payment can be opened for an order that is not CONFIRMED (needs order-service reachable as 'order-service' via discovery, as the fix would)")
    @Test
    void initiate_forAnOrderThatIsNotConfirmed_isRejected() {
        String alice = uniq("alice");
        long orderId = newOrderId();
        registerOrder(orderId, alice, "1000.00", "PENDING");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(orderId, alice, "1000.00"));

        assertThat(response.getStatusCode().is4xxClientError()).as(response.getBody()).isTrue();
        assertThat(rowsForOrder(orderId)).isZero();
    }

    @Disabled("BUG S-1: a payment can be opened for an order owned by someone else (needs order-service reachable as 'order-service' via discovery, as the fix would)")
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

    // ==== State machine and duplicates ======================================================================================

    @Test
    void secondPaymentForAPaidOrder_isRejected_andCannotRewriteTheFirst() {
        String alice = uniq("alice");
        Payment paid = seed(alice, PaymentStatus.COMPLETED, "1000.00");

        ResponseEntity<String> response = asCustomer(alice, HttpMethod.POST, "/api/v1/payments", initiateJson(paid.getOrderId(), alice, "0.01"));

        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(rowsForOrder(paid.getOrderId())).isEqualTo(1);
        Map<String, Object> row = jdbc.queryForMap("select amount, status, transaction_id from payments where id = ?", paid.getId());
        assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("1000.00");
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("transaction_id")).isEqualTo(paid.getTransactionId());
    }

    @Disabled("BUG S-75: re-initiating an unpaid order overwrites customerId, amount and card of the existing row, whoever calls")
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

    @Disabled("BUG S-76: initiate reuses any non-COMPLETED row, so a REFUNDED payment goes back to PENDING and can be completed again")
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
            ResponseEntity<String> response = anon(HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/process", null);
            assertThat(response.getStatusCode().value()).isEqualTo(409);
            assertThat(records(ALL_PAYMENT_TOPICS, Long.toString(payment.getOrderId()), Duration.ofSeconds(2))).isEmpty();
        }
        assertThat(jdbc.queryForObject("select transaction_id from payments where id = ?", String.class, completed.getId())).isEqualTo(completed.getTransactionId());
    }

    @Disabled("BUG S-77: /process reads the status without a lock or version, so concurrent calls on one PENDING payment each run the gateway and each publish an event")
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
                return anon(HttpMethod.POST, "/api/v1/payments/" + payment.getId() + "/process", null).getStatusCode().value();
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

    @Disabled("BUG S-79: initiate does a read-then-insert; concurrent first calls for one order race on uq_payments_order_id and the loser gets a 500")
    @Test
    void concurrentInitiate_forOneOrder_leavesOneRow_andNeverAServerError() throws Exception {
        String alice = uniq("alice");
        long orderId = newOrderId();
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
            ResponseEntity<String> response = http.get().uri("/api/v1/payments/customer/{c}", payload)
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

        ResponseEntity<String> badMethod = http.post().uri("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).body("""
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
                anon(HttpMethod.GET, "/api/v1/payments/987654321", null),
                anon(HttpMethod.GET, "/api/v1/payments/order/987654321", null),
                anon(HttpMethod.POST, "/api/v1/payments/" + completed.getId() + "/process", null),
                anon(HttpMethod.POST, "/api/v1/payments/987654321/refund", null),
                anon(HttpMethod.POST, "/api/v1/payments", initiateJson(newOrderId(), "alice", "-1")),
                anon(HttpMethod.POST, "/api/v1/payments", "{not json"),
                anon(HttpMethod.PUT, "/api/v1/payments/1", null),
                anon(HttpMethod.GET, "/api/v1/payments/nope/nope", null));

        for (ResponseEntity<String> error : errors) {
            assertThat(error.getStatusCode().is4xxClientError()).as(error.getBody()).isTrue();
            assertThat(error.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_JSON)).isTrue();
            assertThat(error.getBody()).doesNotContain("\tat ", "org.springframework", "org.hibernate", "org.postgresql", "select ", "SQLState", "constraint");
        }
    }
}
