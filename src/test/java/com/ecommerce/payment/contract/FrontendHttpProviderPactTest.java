package com.ecommerce.payment.contract;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import com.sun.net.httpserver.HttpServer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Base64;

/**
 * Provider side of the HTTP contract the web frontend (consumer {@code ecommerce-frontend})
 * holds against payment-service.
 *
 * <p>The pact lives in its own folder ({@code src/test/resources/pacts-frontend}) so the
 * Kafka message verification in {@link PaymentEventsProviderPactTest} ({@code pacts}) never
 * sees these HTTP interactions and vice versa.
 *
 * <p>The SPA calls through the API gateway, which validates the JWT and injects
 * {@code X-Username}, {@code X-User-Id} and {@code X-User-Role}; paths are not rewritten.
 * {@link #verifyPact} reproduces the header injection from the bearer token in the pact request.
 *
 * <p>{@code POST /payments/501/process} succeeds or fails at random (80/20) in production code;
 * the pact accepts either terminal status (COMPLETED or FAILED), so no outcome control is needed.
 * Kafka publishing is mocked, the database is H2.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Provider("payment-service")
@PactFolder("pacts-frontend")
class FrontendHttpProviderPactTest {

    /**
     * Stand-in for order-service: initiating a payment asks it for the order (status, owner, total).
     * Registered through the simple discovery client, so the real Feign client and load balancer run.
     */
    private static volatile String orderJson = "{}";
    private static final HttpServer ORDER_SERVICE;

    static {
        try {
            ORDER_SERVICE = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        ORDER_SERVICE.createContext("/internal/v1/orders/", exchange -> {
            byte[] bytes = orderJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        ORDER_SERVICE.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> ORDER_SERVICE.stop(0)));
    }

    @DynamicPropertySource
    static void orderServiceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.cloud.discovery.client.simple.instances.order-service[0].uri",
                () -> "http://127.0.0.1:" + ORDER_SERVICE.getAddress().getPort());
    }

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate; // no broker needed

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context, HttpRequest request) {
        addGatewayIdentityHeaders(request);
        context.verifyInteraction();
    }

    /** What api-gateway's JwtAuthenticationFilter does for an authenticated route (signature not checked). */
    static void addGatewayIdentityHeaders(HttpRequest request) {
        Header auth = request.getFirstHeader("Authorization");
        if (auth == null || !auth.getValue().startsWith("Bearer ")) {
            return; // public route: the gateway adds nothing
        }
        String payload = auth.getValue().substring("Bearer ".length()).split("\\.")[1];
        JsonNode claims = JsonMapper.builder().build().readTree(Base64.getUrlDecoder().decode(payload));
        request.setHeader("X-Username", text(claims, "sub"));
        request.setHeader("X-User-Id", text(claims, "userId"));
        request.setHeader("X-User-Role", text(claims, "role"));
    }

    private static String text(JsonNode claims, String name) {
        return claims.has(name) ? claims.get(name).asText() : "";
    }

    // ───────────────────────── provider states ─────────────────────────

    /** Order 7 belongs to alice, is CONFIRMED and costs 99.98 (what order-service would report). */
    @State("order 7 has no payment")
    void order7HasNoPayment() {
        jdbc.update("DELETE FROM payments");
        orderJson = "{\"orderId\":7,\"customerId\":\"alice\",\"totalAmount\":99.98,\"status\":\"CONFIRMED\",\"orderItems\":[]}";
    }

    /** PENDING payment 501 for order 7 (alice, 99.98 USD, credit card 4242). */
    @State("payment 501 for order 7 is pending")
    void payment501Pending() {
        jdbc.update("DELETE FROM payments");
        insertPayment(501L, 7L, "PENDING", null);
    }

    /** COMPLETED payment 501 for order 7. */
    @State("payment 501 for order 7 has completed")
    void payment501Completed() {
        jdbc.update("DELETE FROM payments");
        insertPayment(501L, 7L, "COMPLETED", "TXN-AB12CD34");
    }

    private void insertPayment(Long id, Long orderId, String status, String transactionId) {
        jdbc.update("INSERT INTO payments (id, order_id, customer_id, amount, currency, status, method, transaction_id, "
                        + "card_last_four, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id, orderId, "alice", new BigDecimal("99.98"), "USD", status, "CREDIT_CARD", transactionId,
                "4242", LocalDateTime.now(), LocalDateTime.now());
    }
}
