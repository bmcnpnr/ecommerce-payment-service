package com.ecommerce.payment.controller;

import com.ecommerce.payment.dto.InitiatePaymentRequest;
import com.ecommerce.payment.dto.PaymentDTO;
import com.ecommerce.payment.exception.PaymentNotFoundException;
import com.ecommerce.payment.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PaymentController.class)
class PaymentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @MockitoBean
    private PaymentService paymentService;

    @Test
    void initiatePayment_returns201() throws Exception {
        InitiatePaymentRequest request = InitiatePaymentRequest.builder()
                .orderId(1L).customerId("cust-1")
                .amount(new BigDecimal("99.99")).method("CREDIT_CARD").build();
        PaymentDTO response = PaymentDTO.builder()
                .id(1L).orderId(1L).customerId("cust-1")
                .amount(new BigDecimal("99.99")).status("PENDING").method("CREDIT_CARD").build();

        when(paymentService.initiatePayment(any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.orderId").value(1));
    }

    @Test
    void getPaymentById_found_returns200() throws Exception {
        PaymentDTO response = PaymentDTO.builder()
                .id(1L).orderId(1L).status("COMPLETED").method("CREDIT_CARD").build();

        when(paymentService.getPaymentById(1L)).thenReturn(response);

        mockMvc.perform(get("/api/v1/payments/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test
    void getPaymentById_notFound_returns404() throws Exception {
        when(paymentService.getPaymentById(99L)).thenThrow(new PaymentNotFoundException("Payment not found: 99"));

        mockMvc.perform(get("/api/v1/payments/99"))
                .andExpect(status().isNotFound());
    }

    @Test
    void initiatePayment_passesRequestThroughToService() throws Exception {
        when(paymentService.initiatePayment(any())).thenReturn(PaymentDTO.builder().id(7L).status("PENDING").build());

        mockMvc.perform(post("/api/v1/payments").contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderId":5,"customerId":"cust-5","amount":12.3456,"currency":"EUR",
                         "method":"DIGITAL_WALLET","cardLastFour":"9876"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(7));

        ArgumentCaptor<InitiatePaymentRequest> req = ArgumentCaptor.forClass(InitiatePaymentRequest.class);
        verify(paymentService).initiatePayment(req.capture());
        assertThat(req.getValue().getOrderId()).isEqualTo(5L);
        assertThat(req.getValue().getCustomerId()).isEqualTo("cust-5");
        assertThat(req.getValue().getAmount()).isEqualByComparingTo("12.3456");
        assertThat(req.getValue().getCurrency()).isEqualTo("EUR");
        assertThat(req.getValue().getMethod()).isEqualTo("DIGITAL_WALLET");
        assertThat(req.getValue().getCardLastFour()).isEqualTo("9876");
    }

    @Test
    void getByOrderId_returns200() throws Exception {
        when(paymentService.getPaymentByOrderId(5L))
                .thenReturn(PaymentDTO.builder().id(3L).orderId(5L).status("PENDING").build());

        mockMvc.perform(get("/api/v1/payments/order/5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(3))
                .andExpect(jsonPath("$.orderId").value(5));
    }

    @Test
    void getByCustomer_returnsList() throws Exception {
        when(paymentService.getPaymentsByCustomer("cust-1")).thenReturn(List.of(
                PaymentDTO.builder().id(1L).customerId("cust-1").build(),
                PaymentDTO.builder().id(2L).customerId("cust-1").build()));
        when(paymentService.getPaymentsByCustomer("nobody")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/payments/customer/cust-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[1].id").value(2));
        mockMvc.perform(get("/api/v1/payments/customer/nobody"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void processPayment_returns200WithResult() throws Exception {
        when(paymentService.processPayment(1L)).thenReturn(PaymentDTO.builder()
                .id(1L).status("COMPLETED").transactionId("TXN-ABCDEF12").build());

        mockMvc.perform(post("/api/v1/payments/1/process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.transactionId").value("TXN-ABCDEF12"));
    }

    @Test
    void refundPayment_returns200() throws Exception {
        when(paymentService.refundPayment(1L)).thenReturn(PaymentDTO.builder().id(1L).status("REFUNDED").build());

        mockMvc.perform(post("/api/v1/payments/1/refund"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }
}
