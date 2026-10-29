package com.ecommerce.payment.security;

import com.ecommerce.payment.controller.PaymentController;
import com.ecommerce.payment.dto.InitiatePaymentRequest;
import com.ecommerce.payment.dto.PaymentDTO;
import com.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * What a hostile client can put in a payment request, with PaymentService mocked: invalid money,
 * currency and card data must be refused at the edge of the service, never stored, and never echoed
 * back. Authorization and state-machine abuse are in PaymentSecurityIT / PaymentServiceSecurityTest.
 */
@Tag("security")
@WebMvcTest(PaymentController.class)
class PaymentControllerSecurityTest {

    private static final String PAN = "4111111111111111";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private PaymentService paymentService;

    private static String body(String amount, String currency, String card) {
        return """
                {"orderId":1,"customerId":"alice","amount":%s,"currency":%s,"method":"CREDIT_CARD","cardLastFour":%s}"""
                .formatted(amount, currency, card);
    }

    private MvcResult initiate(String json) throws Exception {
        return mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    // ---- amount ----------------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-1", "-0.01", "-100000", "0.001", "null", "\"abc\"", "\"\"", "[]", "{}"})
    void initiate_zeroNegativeOrUnparseableAmount_is400_andIsNeverProcessed(String amount) throws Exception {
        MvcResult result = initiate(body(amount, "\"USD\"", "\"4242\""));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(400);
        verifyNoInteractions(paymentService);
    }

    @Disabled("BUG S-71: amount has @DecimalMin(0.01) but no @Digits, so sub-cent precision (0.011, 1.005) is accepted and stored with 4 decimals")
    @ParameterizedTest
    @ValueSource(strings = {"0.011", "1.005", "19.999", "10.12345"})
    void initiate_amountWithMoreThanTwoDecimals_is400(String amount) throws Exception {
        MvcResult result = initiate(body(amount, "\"USD\"", "\"4242\""));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(paymentService);
    }

    @Disabled("BUG S-70: amount has no upper bound or integer-digit limit, so values beyond NUMERIC(19,4) pass validation and fail in the database")
    @ParameterizedTest
    @ValueSource(strings = {"99999999999999999999.99", "1000000000000000.00", "123456789012345678901234567890", "1e400"})
    void initiate_amountBeyondTheColumn_is400(String amount) throws Exception {
        MvcResult result = initiate(body(amount, "\"USD\"", "\"4242\""));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(paymentService);
    }

    // ---- currency ---------------------------------------------------------------------------------------

    @Disabled("BUG S-72: currency only has @Size(max=3), so '<b>', '12$', 'AB' and '' are accepted and stored")
    @ParameterizedTest
    @ValueSource(strings = {"<b>", "12$", "AB", "", "' O"})
    void initiate_currencyThatIsNotAThreeLetterCode_is400(String currency) throws Exception {
        MvcResult result = initiate(body("10.00", "\"" + currency + "\"", "\"4242\""));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"USDX", "<script>", "DROP TABLE payments"})
    void initiate_currencyLongerThanThreeChars_is400(String currency) throws Exception {
        MvcResult result = initiate(body("10.00", "\"" + currency + "\"", "\"4242\""));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(paymentService);
    }

    // ---- card data -----------------------------------------------------------------------------------------

    @Test
    void initiate_fullCardNumber_is400_isNeverPassedOn_andIsNotEchoed() throws Exception {
        MvcResult result = initiate(body("10.00", "\"USD\"", "\"" + PAN + "\""));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(PAN);
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"<script>", "<script>alert(1)</script>", "12345", "'; DROP TABLE payments;--"})
    void initiate_cardLastFourLongerThanFour_is400_andIsNotEchoed(String card) throws Exception {
        MvcResult result = initiate(body("10.00", "\"USD\"", "\"" + card.replace("\"", "\\\"") + "\""));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(card);
        verifyNoInteractions(paymentService);
    }

    @Disabled("BUG S-73: cardLastFour only has @Size(max=4): '<b>', '12', 'abcd' and '' are accepted although the field must be exactly four digits")
    @ParameterizedTest
    @ValueSource(strings = {"<b>", "12", "abcd", "", "12 3", "١٢٣٤"})
    void initiate_cardLastFourThatIsNotFourDigits_is400(String card) throws Exception {
        MvcResult result = initiate(body("10.00", "\"USD\"", "\"" + card + "\""));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        verifyNoInteractions(paymentService);
    }

    @Test
    void initiate_wellFormedRequest_reachesTheService_withExactlyTheValidatedValues() throws Exception {
        given(paymentService.initiatePayment(any())).willReturn(PaymentDTO.builder().id(1L).status("PENDING").method("CREDIT_CARD").build());

        MvcResult result = initiate(body("10.50", "\"EUR\"", "\"4242\""));

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        ArgumentCaptor<InitiatePaymentRequest> sent = ArgumentCaptor.forClass(InitiatePaymentRequest.class);
        verify(paymentService).initiatePayment(sent.capture());
        assertThat(sent.getValue().getAmount()).isEqualByComparingTo("10.50");
        assertThat(sent.getValue().getCurrency()).isEqualTo("EUR");
        assertThat(sent.getValue().getCardLastFour()).isEqualTo("4242");
    }

    // ---- identifiers ----------------------------------------------------------------------------------------

    @Test
    void initiate_missingOrBlankIdentifiers_is400() throws Exception {
        for (String json : new String[]{
                "{\"customerId\":\"alice\",\"amount\":10,\"method\":\"CREDIT_CARD\"}",
                "{\"orderId\":1,\"customerId\":\"\",\"amount\":10,\"method\":\"CREDIT_CARD\"}",
                "{\"orderId\":1,\"customerId\":\"   \",\"amount\":10,\"method\":\"CREDIT_CARD\"}",
                "{\"orderId\":1,\"amount\":10,\"method\":\"CREDIT_CARD\"}",
                "{\"orderId\":1,\"customerId\":\"alice\",\"amount\":10}",
                "{\"orderId\":1,\"customerId\":\"alice\",\"amount\":10,\"method\":\"\"}",
                "{\"orderId\":\"1 OR 1=1\",\"customerId\":\"alice\",\"amount\":10,\"method\":\"CREDIT_CARD\"}",
                "{\"orderId\":99999999999999999999,\"customerId\":\"alice\",\"amount\":10,\"method\":\"CREDIT_CARD\"}"}) {
            assertThat(initiate(json).getResponse().getStatus()).as(json).isEqualTo(400);
        }
        verifyNoInteractions(paymentService);
    }

    @Disabled("BUG S-74: orderId and customerId are not range/length checked: orderId 0 or -1 and a 300-char customerId pass validation (the latter is a 500 at the database)")
    @Test
    void initiate_nonPositiveOrderIdOrOverlongCustomerId_is400() throws Exception {
        for (String json : new String[]{
                "{\"orderId\":0,\"customerId\":\"alice\",\"amount\":10,\"method\":\"CREDIT_CARD\"}",
                "{\"orderId\":-1,\"customerId\":\"alice\",\"amount\":10,\"method\":\"CREDIT_CARD\"}",
                "{\"orderId\":1,\"customerId\":\"" + "a".repeat(300) + "\",\"amount\":10,\"method\":\"CREDIT_CARD\"}"}) {
            assertThat(initiate(json).getResponse().getStatus()).as(json).isEqualTo(400);
        }
        verifyNoInteractions(paymentService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1%20OR%201=1", "abc", "-", "9999999999999999999999"})
    void idPathVariable_thatIsNotALong_is400_andNeverReachesTheService(String id) throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + id)).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/payments/order/" + id)).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/payments/" + id + "/process")).andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/payments/" + id + "/refund")).andExpect(status().isBadRequest());

        verifyNoInteractions(paymentService);
    }

    // ---- request shape ---------------------------------------------------------------------------------------------

    @Test
    void wrongContentTypeAndMalformedBodies_are4xx() throws Exception {
        for (String type : new String[]{"text/plain", "application/xml", "application/x-www-form-urlencoded"}) {
            assertThat(mockMvc.perform(post("/api/v1/payments").contentType(type).content("orderId=1")).andReturn()
                    .getResponse().getStatus()).as(type).isEqualTo(415);
        }
        for (String json : new String[]{"{not json", "", "null", "[]", "\"x\"", "{\"orderId\":1,"}) {
            assertThat(initiate(json).getResponse().getStatus()).as(json).isEqualTo(400);
        }
        verifyNoInteractions(paymentService);
    }

    @Test
    void unsupportedMethods_are405_soNoPaymentCanBeEditedOrDeletedOrListed() throws Exception {
        mockMvc.perform(put("/api/v1/payments/1")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(delete("/api/v1/payments/1")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/api/v1/payments")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/api/v1/payments/1/refund")).andExpect(status().isMethodNotAllowed());
        mockMvc.perform(get("/api/v1/payments/1/process")).andExpect(status().isMethodNotAllowed());

        verifyNoInteractions(paymentService);
    }

    // ---- error bodies ------------------------------------------------------------------------------------------------

    private static void assertNoInternals(String body) {
        for (String marker : new String[]{"com.ecommerce", "java.lang", "java.math", "org.springframework", "org.hibernate",
                "org.postgresql", "jakarta.", "tools.jackson", "com.fasterxml", "Exception", "\tat ", "select ", "SQLState"}) {
            assertThat(body).as("error body must not leak '%s'", marker).doesNotContain(marker);
        }
    }

    @Test
    void unexpectedFailure_is500WithAGenericBody() throws Exception {
        given(paymentService.getPaymentById(5L)).willThrow(new org.springframework.dao.DataIntegrityViolationException(
                "could not execute statement [ERROR: duplicate key value violates unique constraint \"uq_payments_order_id\"] [insert into payments ...]"));

        MvcResult result = mockMvc.perform(get("/api/v1/payments/5")).andExpect(status().isInternalServerError()).andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("An unexpected error occurred").doesNotContain("uq_payments_order_id").doesNotContain("insert into");
        assertNoInternals(body);
    }

    @Test
    void validationError_listsFieldsAndMessagesOnly() throws Exception {
        MvcResult result = initiate(body("-1", "\"USD\"", "\"4242\""));

        String json = result.getResponse().getContentAsString();
        assertThat(json).contains("Validation failed").contains("amount");
        assertNoInternals(json);
    }

    @Disabled("BUG S-78: HttpMessageNotReadableException / MethodArgumentTypeMismatchException messages are echoed verbatim, exposing DTO class names and Java types")
    @Test
    void malformedJson_errorBodyLeaksNoClassNames() throws Exception {
        MvcResult result = initiate(body("\"abc\"", "\"USD\"", "\"4242\""));

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertNoInternals(result.getResponse().getContentAsString());
    }

    @Disabled("BUG S-78: HttpMessageNotReadableException / MethodArgumentTypeMismatchException messages are echoed verbatim, exposing DTO class names and Java types")
    @Test
    void wrongTypedPathVariable_errorBodyLeaksNoClassNames() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/payments/abc")).andExpect(status().isBadRequest()).andReturn();

        assertNoInternals(result.getResponse().getContentAsString());
    }
}
