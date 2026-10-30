package com.ecommerce.payment.messaging;

import com.ecommerce.payment.config.KafkaProducerConfig;
import com.ecommerce.payment.event.PaymentCompletedEvent;
import com.ecommerce.payment.event.PaymentFailedEvent;
import com.ecommerce.payment.event.PaymentRefundedEvent;
import com.ecommerce.payment.outbox.OutboxEvent;
import com.ecommerce.payment.outbox.OutboxEventRepository;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * The producer no longer talks to Kafka: it writes the record to the outbox table in the caller's
 * transaction and OutboxRelay publishes it. What must not change is the record itself: topic, key and the
 * exact JSON bytes consumers and the Pact contracts depend on.
 */
@ExtendWith(MockitoExtension.class)
class PaymentEventProducerTest {

    @Mock OutboxEventRepository outbox;
    @InjectMocks PaymentEventProducer producer;

    private OutboxEvent saved() {
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(captor.capture());
        verifyNoMoreInteractions(outbox);
        return captor.getValue();
    }

    @Test
    void publishCompleted_queuesARecordForPaymentCompletedKeyedByOrderId() {
        producer.publishCompleted(PaymentCompletedEvent.builder().paymentId(1L).orderId(42L).build());

        OutboxEvent row = saved();
        assertThat(row.getTopic()).isEqualTo("payment.completed");
        assertThat(row.getEventKey()).isEqualTo("42");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getParkedAt()).isNull();
    }

    @Test
    void publishFailed_queuesARecordForPaymentFailedKeyedByOrderId() {
        producer.publishFailed(PaymentFailedEvent.builder().paymentId(1L).orderId(43L).build());

        OutboxEvent row = saved();
        assertThat(row.getTopic()).isEqualTo("payment.failed");
        assertThat(row.getEventKey()).isEqualTo("43");
    }

    @Test
    void publishRefunded_queuesARecordForPaymentRefundedKeyedByOrderId() {
        producer.publishRefunded(PaymentRefundedEvent.builder().paymentId(1L).orderId(44L).build());

        OutboxEvent row = saved();
        assertThat(row.getTopic()).isEqualTo("payment.refunded");
        assertThat(row.getEventKey()).isEqualTo("44");
    }

    /**
     * java.time as numeric arrays (what every consumer and the pacts expect), BigDecimal as a JSON
     * number, and no type-info headers.
     */
    @Test
    void payload_writesLocalDateTimeAsIntArray() {
        PaymentCompletedEvent e = PaymentCompletedEvent.builder().paymentId(1L).orderId(42L).customerId("c-1")
                .amount(new BigDecimal("99.99")).transactionId("TXN-1")
                .completedAt(LocalDateTime.of(2026, 10, 29, 13, 45, 7, 123_000_000)).build();

        producer.publishCompleted(e);

        JsonNode json = JsonMapper.builder().build().readTree(saved().getPayload());
        assertThat(json.get("completedAt").isArray()).isTrue();
        assertThat(json.get("completedAt").toString()).isEqualTo("[2026,10,29,13,45,7,123000000]");
        assertThat(json.get("amount").isNumber()).isTrue();
        assertThat(json.get("amount").decimalValue()).isEqualByComparingTo("99.99");
        assertThat(json.get("orderId").asLong()).isEqualTo(42L);
    }

    /** The outbox row holds exactly the bytes the JacksonJsonSerializer producer used to put on the topic. */
    @Test
    void payload_isByteForByteWhatTheOldSerializerWrote() {
        PaymentCompletedEvent e = PaymentCompletedEvent.builder().paymentId(1L).orderId(42L).customerId("c-1")
                .amount(new BigDecimal("99.9900")).transactionId("TXN-1")
                .completedAt(LocalDateTime.of(2026, 10, 29, 13, 45, 7, 123_000_000)).build();
        JacksonJsonSerializer<Object> legacy = new JacksonJsonSerializer<>(KafkaProducerConfig.eventJsonMapper()).noTypeInfo();
        RecordHeaders headers = new RecordHeaders();

        producer.publishCompleted(e);

        assertThat(saved().getPayload().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(legacy.serialize("payment.completed", headers, e));
        assertThat(headers.toArray()).as("no __TypeId__ headers").isEmpty();
    }
}
