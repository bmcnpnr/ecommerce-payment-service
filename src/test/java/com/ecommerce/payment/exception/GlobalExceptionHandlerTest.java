package com.ecommerce.payment.exception;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ecommerce.payment.controller.PaymentController;
import com.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Error paths through the real {@link GlobalExceptionHandler} and {@code CorrelationIdFilter}
 * (both picked up by the MVC slice), with the service mocked.
 */
@WebMvcTest(PaymentController.class)
class GlobalExceptionHandlerTest {

    private static final String VALID_BODY = """
            {"orderId":1,"customerId":"cust-1","amount":10.00,"method":"CREDIT_CARD"}""";

    @Autowired MockMvc mockMvc;
    @MockitoBean PaymentService paymentService;
    // Authorization is covered by PaymentAccessTest and PaymentSecurityIT, the order check by OrderVerifierTest
    // and PaymentSecurityIT; here the controller wiring is under test, so both guards are inert mocks.
    @MockitoBean private com.ecommerce.payment.security.PaymentAccess access;
    @MockitoBean private com.ecommerce.payment.service.OrderVerifier orderVerifier;

    @Test
    void paymentNotFound_returns404WithErrorBodyAndCorrelationId() throws Exception {
        when(paymentService.getPaymentById(99L)).thenThrow(new PaymentNotFoundException("Payment not found: 99"));

        mockMvc.perform(get("/api/v1/payments/99").header("X-Correlation-Id", "cid-404"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Correlation-Id", "cid-404"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.error").value("Not Found"))
                .andExpect(jsonPath("$.message").value("Payment not found: 99"))
                .andExpect(jsonPath("$.path").value("/api/v1/payments/99"))
                .andExpect(jsonPath("$.correlationId").value("cid-404"))
                .andExpect(jsonPath("$.timestamp").value(notNullValue()));
    }

    @Test
    void paymentNotFoundByOrder_returns404() throws Exception {
        when(paymentService.getPaymentByOrderId(5L)).thenThrow(new PaymentNotFoundException("Payment not found for order: 5"));

        mockMvc.perform(get("/api/v1/payments/order/5"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Payment not found for order: 5"))
                .andExpect(jsonPath("$.correlationId").value(notNullValue()));
    }

    @Test
    void paymentAlreadyProcessed_onProcess_returns409() throws Exception {
        when(paymentService.processPayment(1L)).thenThrow(new PaymentAlreadyProcessedException("Payment already in status: COMPLETED"));

        mockMvc.perform(post("/api/v1/payments/1/process"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.error").value("Conflict"))
                .andExpect(jsonPath("$.message").value("Payment already in status: COMPLETED"))
                .andExpect(jsonPath("$.path").value("/api/v1/payments/1/process"));
    }

    @Test
    void paymentAlreadyProcessed_onInitiate_returns409() throws Exception {
        when(paymentService.initiatePayment(any())).thenThrow(new PaymentAlreadyProcessedException("Payment already completed for order: 1"));

        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Payment already completed for order: 1"));
    }

    @Test
    void invalidPaymentState_onRefund_returns400() throws Exception {
        when(paymentService.refundPayment(1L)).thenThrow(new InvalidPaymentStateException("Can only refund COMPLETED payments. Current: PENDING"));

        mockMvc.perform(post("/api/v1/payments/1/refund"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Can only refund COMPLETED payments. Current: PENDING"))
                .andExpect(jsonPath("$.path").value("/api/v1/payments/1/refund"));
    }

    @Test
    void validation_emptyBody_reportsEveryRequiredField() throws Exception {
        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{}")
                        .header("X-Correlation-Id", "cid-val"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.path").value("/api/v1/payments"))
                .andExpect(jsonPath("$.correlationId").value("cid-val"))
                .andExpect(jsonPath("$.fieldErrors", hasSize(4)))
                .andExpect(jsonPath("$.fieldErrors[*].field")
                        .value(containsInAnyOrder("orderId", "customerId", "amount", "method")));
        verifyNoInteractions(paymentService);
    }

    @Test
    void validation_amountBelowMinimum_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderId":1,"customerId":"c","amount":0.00,"method":"CREDIT_CARD"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors", hasSize(1)))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("amount"));
        verifyNoInteractions(paymentService);
    }

    @Test
    void validation_blankStringsAndOversizedFields_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderId":1,"customerId":"  ","amount":5,"currency":"EURO","method":"",
                         "cardLastFour":"12345"}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[*].field")
                        .value(containsInAnyOrder("customerId", "currency", "method", "cardLastFour")));
        verifyNoInteractions(paymentService);
    }

    @Test
    void malformedJson_returns400NotServerError() throws Exception {
        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.error").value("Bad Request"));
        verifyNoInteractions(paymentService);
    }

    @Test
    void nonNumericPathId_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/payments/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.path").value("/api/v1/payments/abc"));
        verifyNoInteractions(paymentService);
    }

    @Test
    void unsupportedHttpMethod_returns405() throws Exception {
        mockMvc.perform(delete("/api/v1/payments/1"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.status").value(405))
                .andExpect(jsonPath("$.error").value("Method Not Allowed"));
    }

    @Test
    void unexpectedException_returns500WithoutLeakingDetails() throws Exception {
        when(paymentService.getPaymentById(1L)).thenThrow(new IllegalStateException("db password is hunter2"));

        mockMvc.perform(get("/api/v1/payments/1").header("X-Correlation-Id", "cid-500"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value(500))
                .andExpect(jsonPath("$.error").value("Internal Server Error"))
                .andExpect(jsonPath("$.message").value("An unexpected error occurred"))
                .andExpect(jsonPath("$.correlationId").value("cid-500"));
    }

    @Test
    void unsupportedContentType_returns415() throws Exception {
        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.TEXT_PLAIN).content(VALID_BODY))
                .andExpect(status().isUnsupportedMediaType());
        verifyNoInteractions(paymentService);
    }

    @Test
    void unknownPath_returns404() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound());
    }

    // ---- dependencies that do not answer are a 503 and one line, not a 500 and a stack trace (L3, L4) ----

    private ListAppender<ILoggingEvent> logs;
    private Logger handlerLogger;

    @BeforeEach
    void captureHandlerLog() {
        handlerLogger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logs = new ListAppender<>();
        logs.start();
        handlerLogger.addAppender(logs);
    }

    @AfterEach
    void releaseHandlerLog() {
        handlerLogger.detachAppender(logs);
    }

    static java.util.stream.Stream<RuntimeException> databaseOutages() {
        java.sql.SQLException cause = new java.sql.SQLException("connect to secret-host:5432 refused");
        return java.util.stream.Stream.of(
                new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", cause),
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction", cause),
                new DataAccessResourceFailureException("connection reset", cause),
                new QueryTimeoutException("statement timeout", cause),
                new CannotAcquireLockException("lock timeout", cause),
                // wrapped by something else on the way up
                new IllegalStateException("tx failed", new CannotGetJdbcConnectionException("pool", cause)));
    }

    @ParameterizedTest
    @MethodSource("databaseOutages")
    void databaseTrouble_is503WithAFixedMessage(RuntimeException outage) throws Exception {
        when(paymentService.getPaymentById(1L)).thenThrow(outage);

        mockMvc.perform(get("/api/v1/payments/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Service temporarily unavailable"))
                .andExpect(jsonPath("$.correlationId").value(notNullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret-host"))));
    }

    @Test
    void optimisticLockConflict_isStill409NotAnOutage() throws Exception {
        when(paymentService.processPayment(1L))
                .thenThrow(new org.springframework.orm.ObjectOptimisticLockingFailureException(com.ecommerce.payment.model.Payment.class, 1L));

        mockMvc.perform(post("/api/v1/payments/1/process"))
                .andExpect(status().isConflict());
    }

    private static final class DatabaseGoneForThisTest extends CannotGetJdbcConnectionException {
        DatabaseGoneForThisTest() {
            super("pool exhausted");
        }
    }

    private static final class BugForThisTest extends RuntimeException {
        BugForThisTest() {
            super("boom");
        }
    }

    /** A dead database under load must cost one line, not one per request, and no stack trace. */
    @Test
    void aHundredRequestsDuringAnOutage_writeOneWarnWithoutStackTrace() throws Exception {
        when(paymentService.getPaymentById(1L)).thenThrow(new DatabaseGoneForThisTest());

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/v1/payments/1")).andExpect(status().isServiceUnavailable());
        }

        assertThat(logs.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).hasSize(1)
                .allSatisfy(e -> {
                    assertThat(e.getLevel()).isEqualTo(Level.WARN);
                    assertThat(e.getThrowableProxy()).as("no stack trace for a dead dependency").isNull();
                });
    }

    /** A bug on a hot path logs its stack trace once per window, however many requests hit it. */
    @Test
    void aHundredRequestsHittingOneBug_writeOneStackTrace() throws Exception {
        when(paymentService.getPaymentById(1L)).thenThrow(new BugForThisTest());

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/v1/payments/1")).andExpect(status().isInternalServerError());
        }

        assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR).hasSize(1)
                .allSatisfy(e -> assertThat(e.getThrowableProxy()).isNotNull());
    }

    @Test
    void orderServiceDown_is503WithTheOrderServiceMessage() throws Exception {
        when(paymentService.getPaymentById(2L))
                .thenThrow(new DependencyUnavailableException("order-service unavailable", new RuntimeException("refused")));

        mockMvc.perform(get("/api/v1/payments/2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.message").value("Order service unavailable. Please try again later."));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"101,0", "0,0", "-1,0", "100,-1", "100000000,0"})
    void customerListOutOfRange_is400AndNeverReachesTheDatabase(int size, int page) throws Exception {
        mockMvc.perform(get("/api/v1/payments/customer/cust-1").param("size", String.valueOf(size)).param("page", String.valueOf(page)))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    /**
     * A killed database backend: the rollback fails too, and Spring's TransactionSystemException replaces the original
     * error. It is an outage (503, one line), not a bug (500 and hundreds of lines of stack trace).
     */
    @Test
    void aRollbackThatFailedBecauseTheBackendWasKilled_is503_andCostsOneLineForAHundredRequests() throws Exception {
        org.springframework.transaction.TransactionSystemException failure = new org.springframework.transaction.TransactionSystemException(
                "Could not roll back JPA transaction", new jakarta.persistence.PersistenceException("Unable to rollback against JDBC Connection",
                new java.sql.SQLException("This connection has been closed. secret-host", "08003")));
        when(paymentService.getPaymentById(1L)).thenThrow(failure);

        for (int i = 0; i < 100; i++) {
            mockMvc.perform(get("/api/v1/payments/1"))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.message").value("Service temporarily unavailable"))
                    .andExpect(content().string(org.hamcrest.Matchers.not(containsString("secret-host"))));
        }

        assertThat(logs.list).filteredOn(e -> e.getLevel().isGreaterOrEqual(Level.WARN)).hasSizeLessThanOrEqualTo(1)
                .allSatisfy(e -> {
                    assertThat(e.getThrowableProxy()).as("no stack trace for a dead database").isNull();
                    assertThat(e.getFormattedMessage()).contains("TransactionSystemException (SQLException: This connection has been closed.");
                });
    }
}
