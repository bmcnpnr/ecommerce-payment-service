package com.ecommerce.payment.security;

import com.ecommerce.payment.dto.InitiatePaymentRequest;
import com.ecommerce.payment.event.PaymentRefundedEvent;
import com.ecommerce.payment.exception.InvalidPaymentMethodException;
import com.ecommerce.payment.exception.InvalidPaymentStateException;
import com.ecommerce.payment.exception.PaymentAlreadyProcessedException;
import com.ecommerce.payment.messaging.PaymentEventProducer;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentMethod;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Payment state machine and ownership of a payment row, with the repository mocked. A refused
 * operation must leave the payment untouched and publish nothing.
 */
@Tag("security")
@ExtendWith(MockitoExtension.class)
class PaymentServiceSecurityTest {

    @Mock private PaymentRepository paymentRepository;
    @Mock private PaymentEventProducer eventProducer;

    private PaymentService service;

    @BeforeEach
    void setUp() {
        service = new PaymentService(paymentRepository, eventProducer);
        lenient().when(paymentRepository.save(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
    }

    private Payment payment(PaymentStatus status) {
        Payment payment = Payment.builder().id(7L).orderId(100L).customerId("alice").amount(new BigDecimal("250.00"))
                .currency("USD").method(PaymentMethod.CREDIT_CARD).status(status).cardLastFour("4242").build();
        if (status == PaymentStatus.COMPLETED || status == PaymentStatus.REFUNDED) {
            payment.setTransactionId("TXN-ORIGINAL");
        }
        lenient().when(paymentRepository.findById(7L)).thenReturn(Optional.of(payment));
        lenient().when(paymentRepository.findByOrderId(100L)).thenReturn(Optional.of(payment));
        return payment;
    }

    private static InitiatePaymentRequest request(String customerId, String amount, String method) {
        return InitiatePaymentRequest.builder().orderId(100L).customerId(customerId).amount(new BigDecimal(amount))
                .method(method).cardLastFour("4242").build();
    }

    // ---- refund ------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "PROCESSING", "FAILED", "REFUNDED"})
    void refund_ofAPaymentThatWasNotCollected_orAlreadyRefunded_isRejected_withNoEvent(PaymentStatus status) {
        Payment payment = payment(status);

        assertThatThrownBy(() -> service.refundPayment(7L)).isInstanceOf(InvalidPaymentStateException.class);

        assertThat(payment.getStatus()).isEqualTo(status);
        verifyNoInteractions(eventProducer);
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void refund_refundsExactlyWhatWasPaid_andOnlyOnce() {
        Payment payment = payment(PaymentStatus.COMPLETED);

        service.refundPayment(7L);
        assertThatThrownBy(() -> service.refundPayment(7L)).isInstanceOf(InvalidPaymentStateException.class);

        ArgumentCaptor<PaymentRefundedEvent> event = ArgumentCaptor.forClass(PaymentRefundedEvent.class);
        verify(eventProducer).publishRefunded(event.capture());
        assertThat(event.getValue().getAmount()).isEqualByComparingTo("250.00");
        assertThat(event.getValue().getOrderId()).isEqualTo(100L);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    // ---- process -------------------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PROCESSING", "COMPLETED", "FAILED", "REFUNDED"})
    void process_ofAPaymentThatIsNotPending_isRejected_withNoSecondEvent(PaymentStatus status) {
        Payment payment = payment(status);

        assertThatThrownBy(() -> service.processPayment(7L)).isInstanceOf(PaymentAlreadyProcessedException.class);

        assertThat(payment.getStatus()).isEqualTo(status);
        assertThat(payment.getTransactionId()).isEqualTo(status == PaymentStatus.COMPLETED || status == PaymentStatus.REFUNDED ? "TXN-ORIGINAL" : null);
        verifyNoInteractions(eventProducer);
    }

    // ---- initiate: duplicates and takeover ---------------------------------------------------------------

    @Test
    void initiate_forAnOrderThatIsAlreadyPaid_isRejected_andChangesNothing() {
        Payment payment = payment(PaymentStatus.COMPLETED);

        assertThatThrownBy(() -> service.initiatePayment(request("alice", "0.01", "CREDIT_CARD")))
                .isInstanceOf(PaymentAlreadyProcessedException.class);

        assertThat(payment.getAmount()).isEqualByComparingTo("250.00");
        assertThat(payment.getTransactionId()).isEqualTo("TXN-ORIGINAL");
        verify(paymentRepository, never()).save(any());
    }

    @Test
    void initiate_forARefundedPayment_isRejected_andStaysRefunded() {
        Payment payment = payment(PaymentStatus.REFUNDED);

        assertThatThrownBy(() -> service.initiatePayment(request("alice", "250.00", "CREDIT_CARD")))
                .isInstanceOf(RuntimeException.class);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    @ParameterizedTest
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "FAILED"})
    void initiate_byADifferentCustomer_cannotTakeOverAnExistingPayment(PaymentStatus status) {
        Payment payment = payment(status);

        assertThatThrownBy(() -> service.initiatePayment(request("bob", "0.01", "CREDIT_CARD")))
                .isInstanceOf(PaymentAlreadyProcessedException.class)
                // the same generic answer whatever the state: only the owner is told that the payment was declined
                .hasMessage("A payment already exists for order: 100");

        assertThat(payment.getCustomerId()).isEqualTo("alice");
        assertThat(payment.getAmount()).isEqualByComparingTo("250.00");
        assertThat(payment.getStatus()).isEqualTo(status);
        verify(paymentRepository, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"credit_card", "CREDIT_CARD ", "CREDIT_CARD'; DROP TABLE payments;--", "<script>alert(1)</script>", "0", "ADMIN"})
    void initiate_unknownMethod_isRejected_andNothingIsSaved(String method) {
        assertThatThrownBy(() -> service.initiatePayment(request("alice", "10.00", method)))
                .isInstanceOf(InvalidPaymentMethodException.class);

        verify(paymentRepository, never()).save(any());
    }

    /** A declined payment ends the order (order-service cancels it on payment.failed): there is no second attempt. */
    @ParameterizedTest
    @ValueSource(strings = {"CREDIT_CARD", "DEBIT_CARD", "BANK_TRANSFER"})
    void initiate_afterADecline_byTheOwner_isRejected_andTheRowIsLeftAlone(String method) {
        Payment payment = payment(PaymentStatus.FAILED);
        payment.setFailureReason("Payment declined by gateway");

        assertThatThrownBy(() -> service.initiatePayment(request("alice", "250.00", method)))
                .isInstanceOf(PaymentAlreadyProcessedException.class)
                .hasMessage(PaymentService.DECLINED_ORDER_MESSAGE);

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getFailureReason()).isEqualTo("Payment declined by gateway");
        assertThat(payment.getMethod()).isEqualTo(PaymentMethod.CREDIT_CARD);
        verify(paymentRepository, never()).save(any());
        verifyNoInteractions(eventProducer);
    }

    @Test
    void initiate_retryOfAPendingAttempt_resetsServerControlledFields() {
        Payment payment = payment(PaymentStatus.PENDING);
        payment.setFailureReason("left over");
        payment.setTransactionId("TXN-STALE");

        service.initiatePayment(request("alice", "250.00", "CREDIT_CARD"));

        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getTransactionId()).isNull();
        assertThat(payment.getFailureReason()).isNull();
    }
}
