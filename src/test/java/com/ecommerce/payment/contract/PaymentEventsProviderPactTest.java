package com.ecommerce.payment.contract;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junit5.PactVerificationInvocationContextProvider;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.junitsupport.loader.PactFolder;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentMethod;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.outbox.OutboxEvent;
import com.ecommerce.payment.outbox.OutboxEventRepository;
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provider side of the Kafka contracts for the {@code payment.*} events.
 *
 * <p>order-service, shipping-service and notification-service each hold a pact
 * describing the {@code payment.completed} / {@code payment.failed} /
 * {@code payment.refunded} record they can consume (copies live in
 * {@code src/test/resources/pacts/}, see {@code ecommerce-platform/sync-pacts.sh}).
 * For every interaction this class produces the record the way production does:
 * the real {@link PaymentService} builds the event, the real
 * {@code PaymentEventProducer} serializes it and writes it to the outbox table (the repository is
 * mocked here to capture topic, key and payload; {@code OutboxRelay} sends exactly these bytes to Kafka).
 * Pact then matches bytes and metadata against each consumer's expectations.
 *
 * <p>{@code processPayment} decides success/failure with a random number (80/20), so
 * the completed/failed states simply re-run it on a fresh PENDING payment until the
 * wanted branch fires — every attempt exercises the production code path.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Provider("payment-service")
@PactFolder("pacts")
class PaymentEventsProviderPactTest {

    private static final int MAX_ATTEMPTS = 500; // P(miss) = 0.8^500 or 0.2^500 — never in practice

    @Autowired
    private PaymentService paymentService;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private OutboxEventRepository outboxRepository;

    @BeforeEach
    void setTarget(PactVerificationContext context) {
        context.setTarget(new MessageTestTarget());
    }

    @TestTemplate
    @ExtendWith(PactVerificationInvocationContextProvider.class)
    void verifyPact(PactVerificationContext context) {
        context.verifyInteraction();
    }

    // ───────────────────────── provider states ─────────────────────────
    // State names are part of the contract: consumers reference them verbatim.

    @State("payment 501 for order 7 has completed")
    void payment501Pending() {
        stubPendingPayment(501L);
    }

    @State("payment 502 for order 7 has failed")
    void payment502Pending() {
        stubPendingPayment(502L);
    }

    @State("payment 503 for order 7 has been refunded")
    void payment503Completed() {
        reset(paymentRepository);
        reset(outboxRepository);
        when(paymentRepository.findById(503L)).thenAnswer(inv -> {
            Payment completed = payment(503L, PaymentStatus.COMPLETED);
            completed.setTransactionId("TXN-1A2B3C4D");
            return Optional.of(completed);
        });
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    // ───────────────────────── message producers ─────────────────────────
    // Method names are irrelevant; the annotation value must equal the consumer's
    // expectsToReceive(...) description.

    @PactVerifyProvider("a payment.completed event")
    MessageAndMetadata paymentCompleted() {
        return processUntilPublishedOn("payment.completed", 501L);
    }

    @PactVerifyProvider("a payment.failed event")
    MessageAndMetadata paymentFailed() {
        return processUntilPublishedOn("payment.failed", 502L);
    }

    @PactVerifyProvider("a payment.refunded event")
    MessageAndMetadata paymentRefunded() {
        paymentService.refundPayment(503L);
        return capturedRecord("payment.refunded");
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void stubPendingPayment(long paymentId) {
        reset(paymentRepository);
        reset(outboxRepository);
        when(paymentRepository.findById(paymentId)).thenAnswer(inv -> Optional.of(payment(paymentId, PaymentStatus.PENDING)));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private MessageAndMetadata processUntilPublishedOn(String topic, long paymentId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            clearInvocations(outboxRepository);
            paymentService.processPayment(paymentId);
            ArgumentCaptor<OutboxEvent> queued = ArgumentCaptor.forClass(OutboxEvent.class);
            verify(outboxRepository).save(queued.capture());
            if (topic.equals(queued.getValue().getTopic())) {
                return capturedRecord(topic);
            }
        }
        throw new AssertionError("processPayment never published on " + topic + " in " + MAX_ATTEMPTS + " attempts");
    }

    /** The (topic, key, value) the production code wrote to the outbox: the bytes OutboxRelay will send. */
    private MessageAndMetadata capturedRecord(String expectedTopic) {
        ArgumentCaptor<OutboxEvent> row = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, atLeastOnce()).save(row.capture());
        OutboxEvent last = row.getValue();
        if (!expectedTopic.equals(last.getTopic())) {
            throw new AssertionError("expected a record on " + expectedTopic + " but the outbox got " + last.getTopic());
        }
        return new MessageAndMetadata(last.getPayload().getBytes(StandardCharsets.UTF_8), Map.of(
                "contentType", "application/json",
                "kafka_topic", expectedTopic,
                "kafka_key", last.getEventKey()));
    }

    private static Payment payment(long id, PaymentStatus status) {
        return Payment.builder()
                .id(id).orderId(7L).customerId("customer-42")
                .amount(new BigDecimal("99.98")).currency("USD")
                .method(PaymentMethod.CREDIT_CARD).cardLastFour("4242")
                .status(status)
                .build();
    }
}
