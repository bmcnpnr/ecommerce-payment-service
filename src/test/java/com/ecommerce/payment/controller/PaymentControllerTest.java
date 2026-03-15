package com.ecommerce.payment.controller;

import com.ecommerce.payment.dto.InitiatePaymentRequest;
import com.ecommerce.payment.dto.PaymentDTO;
import com.ecommerce.payment.exception.PaymentNotFoundException;
import com.ecommerce.payment.service.PaymentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
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
}
