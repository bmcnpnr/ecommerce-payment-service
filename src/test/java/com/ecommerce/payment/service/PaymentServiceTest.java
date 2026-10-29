package com.ecommerce.payment.service;

import com.ecommerce.payment.dto.InitiatePaymentRequest;
import com.ecommerce.payment.dto.PaymentDTO;
import com.ecommerce.payment.event.PaymentCompletedEvent;
import com.ecommerce.payment.event.PaymentFailedEvent;
import com.ecommerce.payment.event.PaymentRefundedEvent;
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
import java.util.ArrayList;
import java.util.List;
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

    // ---- initiatePayment ------------------------------------------------------------------

    @Test
    void initiatePayment_mapsRequestOntoPendingPayment() {
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentDTO result = paymentService.initiatePayment(InitiatePaymentRequest.builder()
                .orderId(10L).customerId("user1").amount(new BigDecimal("12.3400")).currency("EUR")
                .method("DEBIT_CARD").cardLastFour("4242").build());

        ArgumentCaptor<Payment> saved = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).save(saved.capture());
        Payment p = saved.getValue();
        assertThat(p.getOrderId()).isEqualTo(10L);
        assertThat(p.getCustomerId()).isEqualTo("user1");
        assertThat(p.getAmount()).isEqualByComparingTo("12.34");
        assertThat(p.getCurrency()).isEqualTo("EUR");
        assertThat(p.getMethod()).isEqualTo(PaymentMethod.DEBIT_CARD);
        assertThat(p.getCardLastFour()).isEqualTo("4242");
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(p.getTransactionId()).isNull();
        assertThat(p.getCreatedAt()).isNotNull();

        assertThat(result.getMethod()).isEqualTo("DEBIT_CARD");
        assertThat(result.getCurrency()).isEqualTo("EUR");
        assertThat(result.getCardLastFour()).isEqualTo("4242");
        verifyNoInteractions(eventProducer);
    }

    @Test
    void initiatePayment_withoutCurrency_defaultsToUsd() {
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.empty());
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PaymentDTO result = paymentService.initiatePayment(InitiatePaymentRequest.builder()
                .orderId(10L).customerId("user1").amount(BigDecimal.TEN).method("CREDIT_CARD").build());

        assertThat(result.getCurrency()).isEqualTo("USD");
    }

    @Test
    void initiatePayment_existingPendingOrFailedPayment_allowsNewAttempt() {
        for (PaymentStatus existing : new PaymentStatus[]{PaymentStatus.PENDING, PaymentStatus.FAILED,
                PaymentStatus.PROCESSING, PaymentStatus.REFUNDED}) {
            reset(paymentRepository);
            testPayment.setStatus(existing);
            when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.of(testPayment));
            when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            PaymentDTO result = paymentService.initiatePayment(InitiatePaymentRequest.builder()
                    .orderId(10L).customerId("user1").amount(BigDecimal.ONE).method("BANK_TRANSFER").build());

            assertThat(result.getStatus()).as("existing %s", existing).isEqualTo("PENDING");
            verify(paymentRepository).save(any());
        }
    }

    @Test
    void initiatePayment_alreadyCompleted_doesNotSave() {
        testPayment.setStatus(PaymentStatus.COMPLETED);
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.of(testPayment));

        assertThatThrownBy(() -> paymentService.initiatePayment(InitiatePaymentRequest.builder()
                .orderId(10L).customerId("u").amount(BigDecimal.ONE).method("CREDIT_CARD").build()))
                .hasMessageContaining("10");
        verify(paymentRepository, never()).save(any());
    }

    // ---- processPayment -------------------------------------------------------------------

    @Test
    void processPayment_notFound_throws() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.processPayment(99L))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining("99");
        verifyNoInteractions(eventProducer);
    }

    @Test
    void processPayment_anyNonPendingStatus_throwsWithoutSideEffects() {
        for (PaymentStatus status : new PaymentStatus[]{PaymentStatus.PROCESSING, PaymentStatus.COMPLETED,
                PaymentStatus.FAILED, PaymentStatus.REFUNDED}) {
            testPayment.setStatus(status);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(testPayment));

            assertThatThrownBy(() -> paymentService.processPayment(1L))
                    .isInstanceOf(PaymentAlreadyProcessedException.class)
                    .hasMessageContaining(status.name());
            assertThat(testPayment.getStatus()).isEqualTo(status);
        }
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    /**
     * processPayment decides success with an 80/20 random draw, so both branch tests re-run it on
     * a fresh PENDING payment until the wanted branch fires (as the Pact provider test does).
     */
    private PaymentDTO processUntil(String wantedStatus) {
        when(paymentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        for (int i = 0; i < 500; i++) {
            Payment p = Payment.builder().id(1L).orderId(10L).customerId("user1")
                    .amount(new BigDecimal("99.99")).currency("USD").method(PaymentMethod.CREDIT_CARD)
                    .status(PaymentStatus.PENDING).build();
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(p));
            clearInvocations(eventProducer);
            PaymentDTO dto = paymentService.processPayment(1L);
            if (dto.getStatus().equals(wantedStatus)) {
                return dto;
            }
        }
        throw new AssertionError("branch " + wantedStatus + " never taken");
    }

    @Test
    void processPayment_success_completesAndPublishesCompletedEvent() {
        PaymentDTO dto = processUntil("COMPLETED");

        assertThat(dto.getTransactionId()).matches("TXN-[0-9A-F]{8}");
        assertThat(dto.getFailureReason()).isNull();
        assertThat(dto.getUpdatedAt()).isNotNull();

        ArgumentCaptor<PaymentCompletedEvent> ev = ArgumentCaptor.forClass(PaymentCompletedEvent.class);
        verify(eventProducer).publishCompleted(ev.capture());
        verify(eventProducer, never()).publishFailed(any());
        assertThat(ev.getValue().getPaymentId()).isEqualTo(1L);
        assertThat(ev.getValue().getOrderId()).isEqualTo(10L);
        assertThat(ev.getValue().getCustomerId()).isEqualTo("user1");
        assertThat(ev.getValue().getAmount()).isEqualByComparingTo("99.99");
        assertThat(ev.getValue().getTransactionId()).isEqualTo(dto.getTransactionId());
        assertThat(ev.getValue().getCompletedAt()).isNotNull();
    }

    @Test
    void processPayment_failure_marksFailedAndPublishesFailedEvent() {
        PaymentDTO dto = processUntil("FAILED");

        assertThat(dto.getTransactionId()).isNull();
        assertThat(dto.getFailureReason()).isEqualTo("Payment declined by gateway");

        ArgumentCaptor<PaymentFailedEvent> ev = ArgumentCaptor.forClass(PaymentFailedEvent.class);
        verify(eventProducer).publishFailed(ev.capture());
        verify(eventProducer, never()).publishCompleted(any());
        assertThat(ev.getValue().getPaymentId()).isEqualTo(1L);
        assertThat(ev.getValue().getOrderId()).isEqualTo(10L);
        assertThat(ev.getValue().getCustomerId()).isEqualTo("user1");
        assertThat(ev.getValue().getFailureReason()).isEqualTo("Payment declined by gateway");
        assertThat(ev.getValue().getFailedAt()).isNotNull();
    }

    @Test
    void processPayment_persistsProcessingBeforeFinalStatus() {
        List<PaymentStatus> savedStatuses = new ArrayList<>();
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(testPayment));
        when(paymentRepository.save(any())).thenAnswer(inv -> {
            savedStatuses.add(inv.<Payment>getArgument(0).getStatus());
            return inv.getArgument(0);
        });

        PaymentDTO dto = paymentService.processPayment(1L);

        assertThat(savedStatuses).containsExactly(PaymentStatus.PROCESSING, PaymentStatus.valueOf(dto.getStatus()));
        assertThat(dto.getStatus()).isIn("COMPLETED", "FAILED");
    }

    // ---- refundPayment --------------------------------------------------------------------

    @Test
    void refundPayment_completed_refundsAndPublishesRefundedEvent() {
        testPayment.setStatus(PaymentStatus.COMPLETED);
        testPayment.setTransactionId("TXN-ABCDEF12");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(testPayment));

        PaymentDTO dto = paymentService.refundPayment(1L);

        assertThat(dto.getStatus()).isEqualTo("REFUNDED");
        assertThat(dto.getTransactionId()).isEqualTo("TXN-ABCDEF12");
        assertThat(dto.getUpdatedAt()).isNotNull();
        verify(paymentRepository).save(testPayment);

        ArgumentCaptor<PaymentRefundedEvent> ev = ArgumentCaptor.forClass(PaymentRefundedEvent.class);
        verify(eventProducer).publishRefunded(ev.capture());
        assertThat(ev.getValue().getPaymentId()).isEqualTo(1L);
        assertThat(ev.getValue().getOrderId()).isEqualTo(10L);
        assertThat(ev.getValue().getCustomerId()).isEqualTo("user1");
        assertThat(ev.getValue().getAmount()).isEqualByComparingTo("99.99");
        assertThat(ev.getValue().getRefundedAt()).isNotNull();
    }

    @Test
    void refundPayment_notFound_throws() {
        when(paymentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> paymentService.refundPayment(99L))
                .isInstanceOf(PaymentNotFoundException.class);
        verifyNoInteractions(eventProducer);
    }

    @Test
    void refundPayment_anyNonCompletedStatus_throwsWithoutSideEffects() {
        for (PaymentStatus status : new PaymentStatus[]{PaymentStatus.PENDING, PaymentStatus.PROCESSING,
                PaymentStatus.FAILED, PaymentStatus.REFUNDED}) {
            testPayment.setStatus(status);
            when(paymentRepository.findById(1L)).thenReturn(Optional.of(testPayment));

            assertThatThrownBy(() -> paymentService.refundPayment(1L))
                    .isInstanceOf(InvalidPaymentStateException.class)
                    .hasMessageContaining(status.name());
        }
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    // ---- queries --------------------------------------------------------------------------

    @Test
    void getPaymentById_found_mapsAllFields() {
        testPayment.setStatus(PaymentStatus.FAILED);
        testPayment.setFailureReason("declined");
        testPayment.setCardLastFour("1234");
        when(paymentRepository.findById(1L)).thenReturn(Optional.of(testPayment));

        PaymentDTO dto = paymentService.getPaymentById(1L);

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getOrderId()).isEqualTo(10L);
        assertThat(dto.getCustomerId()).isEqualTo("user1");
        assertThat(dto.getAmount()).isEqualByComparingTo("99.99");
        assertThat(dto.getCurrency()).isEqualTo("USD");
        assertThat(dto.getStatus()).isEqualTo("FAILED");
        assertThat(dto.getMethod()).isEqualTo("CREDIT_CARD");
        assertThat(dto.getFailureReason()).isEqualTo("declined");
        assertThat(dto.getCardLastFour()).isEqualTo("1234");
        assertThat(dto.getCreatedAt()).isEqualTo(testPayment.getCreatedAt());
    }

    @Test
    void getPaymentByOrderId_foundAndNotFound() {
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.of(testPayment));
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.empty());

        assertThat(paymentService.getPaymentByOrderId(10L).getId()).isEqualTo(1L);
        assertThatThrownBy(() -> paymentService.getPaymentByOrderId(11L))
                .isInstanceOf(PaymentNotFoundException.class)
                .hasMessageContaining("order: 11");
    }

    @Test
    void getPaymentsByCustomer_mapsEveryPayment() {
        Payment second = Payment.builder().id(2L).orderId(20L).customerId("user1")
                .amount(BigDecimal.ONE).method(PaymentMethod.DIGITAL_WALLET).build();
        when(paymentRepository.findByCustomerId("user1")).thenReturn(List.of(testPayment, second));
        when(paymentRepository.findByCustomerId("nobody")).thenReturn(List.of());

        assertThat(paymentService.getPaymentsByCustomer("user1"))
                .extracting(PaymentDTO::getId, PaymentDTO::getMethod)
                .containsExactly(tuple(1L, "CREDIT_CARD"), tuple(2L, "DIGITAL_WALLET"));
        assertThat(paymentService.getPaymentsByCustomer("nobody")).isEmpty();
    }
}
