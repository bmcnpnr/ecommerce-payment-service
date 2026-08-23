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
import com.ecommerce.payment.repository.PaymentRepository;
import com.ecommerce.payment.service.PaymentService;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
 * {@code PaymentEventProducer} hands it to the {@code KafkaTemplate} (mocked here to
 * capture topic, key and payload), and the payload is serialized with the value
 * serializer configured on the production {@link ProducerFactory} (spring-kafka's
 * {@code JsonSerializer}, no type headers). Pact then matches bytes and metadata
 * against each consumer's expectations.
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

    @Autowired
    private ProducerFactory<String, Object> producerFactory;

    @MockitoBean
    private PaymentRepository paymentRepository;

    @MockitoBean
    private KafkaTemplate<String, Object> kafkaTemplate;

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
        reset(kafkaTemplate);
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
        reset(kafkaTemplate);
        when(paymentRepository.findById(paymentId)).thenAnswer(inv -> Optional.of(payment(paymentId, PaymentStatus.PENDING)));
        when(paymentRepository.save(any(Payment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private MessageAndMetadata processUntilPublishedOn(String topic, long paymentId) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            clearInvocations(kafkaTemplate);
            paymentService.processPayment(paymentId);
            ArgumentCaptor<String> sentTopic = ArgumentCaptor.forClass(String.class);
            verify(kafkaTemplate).send(sentTopic.capture(), anyString(), any());
            if (topic.equals(sentTopic.getValue())) {
                return capturedRecord(topic);
            }
        }
        throw new AssertionError("processPayment never published on " + topic + " in " + MAX_ATTEMPTS + " attempts");
    }

    /** The (topic, key, value) the production code handed to KafkaTemplate, serialized exactly as the producer would. */
    private MessageAndMetadata capturedRecord(String expectedTopic) {
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object> value = ArgumentCaptor.forClass(Object.class);
        verify(kafkaTemplate, atLeastOnce()).send(eq(expectedTopic), key.capture(), value.capture());
        byte[] bytes = valueSerializer().serialize(expectedTopic, new RecordHeaders(), value.getValue());
        return new MessageAndMetadata(bytes, Map.of(
                "contentType", "application/json",
                "kafka_topic", expectedTopic,
                "kafka_key", key.getValue()));
    }

    /** Instantiates and configures the value serializer class exactly as the Kafka client would from the production ProducerFactory. */
    @SuppressWarnings("unchecked")
    private Serializer<Object> valueSerializer() {
        Map<String, Object> config = producerFactory.getConfigurationProperties();
        Object configured = config.get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG);
        try {
            Class<?> type = configured instanceof Class<?> c ? c : Class.forName(String.valueOf(configured));
            Serializer<Object> serializer = (Serializer<Object>) type.getDeclaredConstructor().newInstance();
            serializer.configure(config, false);
            return serializer;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot instantiate configured value serializer " + configured, e);
        }
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
