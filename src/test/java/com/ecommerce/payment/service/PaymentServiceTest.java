package com.ecommerce.payment.service;

import com.ecommerce.payment.dto.InitiatePaymentRequest;
import com.ecommerce.payment.dto.PaymentDTO;
import com.ecommerce.payment.exception.*;
import com.ecommerce.payment.messaging.PaymentEventProducer;
import com.ecommerce.payment.model.*;
import com.ecommerce.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock PaymentRepository paymentRepository;
    @Mock PaymentEventProducer eventProducer;
    @InjectMocks PaymentService paymentService;

    private Payment testPayment;

    @BeforeEach
    void setUp() {
        testPayment = Payment.builder().id(1L).orderId(10L).customerId("user1")
                .amount(new BigDecimal("99.99")).currency("USD").method(PaymentMethod.CREDIT_CARD)
                .status(PaymentStatus.PENDING).build();
    }

    @Test
    void initiatePayment_success() {
        InitiatePaymentRequest req = InitiatePaymentRequest.builder()
                .orderId(10L).customerId("user1").amount(new BigDecimal("99.99"))
                .method("CREDIT_CARD").build();
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> { Payment p = inv.getArgument(0); p.setId(1L); return p; });

        PaymentDTO result = paymentService.initiatePayment(req);
        assertThat(result.getOrderId()).isEqualTo(10L);
        assertThat(result.getStatus()).isEqualTo("PENDING");
    }

    @Test
    void initiatePayment_alreadyCompleted_throws() {
        testPayment.setStatus(PaymentStatus.COMPLETED);
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.of(testPayment));

        assertThatThrownBy(() -> paymentService.initiatePayment(
                InitiatePaymentRequest.builder().orderId(10L).customerId("u").amount(BigDecimal.ONE).method("CREDIT_CARD").build()))
                .isInstanceOf(PaymentAlreadyProcessedException.class);
    }

    @Test
    void processPayment_notPending_throws() {
        testPayment.setStatus(PaymentStatus.COMPLETED);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(testPayment));

        assertThatThrownBy(() -> paymentService.processPayment(1L))
                .isInstanceOf(PaymentAlreadyProcessedException.class);
    }

    @Test
    void refundPayment_notCompleted_throws() {
        testPayment.setStatus(PaymentStatus.PENDING);
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(testPayment));

        assertThatThrownBy(() -> paymentService.refundPayment(1L))
                .isInstanceOf(InvalidPaymentStateException.class);
    }

    @Test
    void getPaymentById_notFound_throws() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> paymentService.getPaymentById(99L))
                .isInstanceOf(PaymentNotFoundException.class);
    }
}
