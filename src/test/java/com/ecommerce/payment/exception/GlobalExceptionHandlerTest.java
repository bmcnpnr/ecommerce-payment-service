package com.ecommerce.payment.exception;

import com.ecommerce.payment.controller.PaymentController;
import com.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsInAnyOrder;
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
}
