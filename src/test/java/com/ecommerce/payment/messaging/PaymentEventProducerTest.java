package com.ecommerce.payment.messaging;

import com.ecommerce.payment.config.KafkaProducerConfig;
import com.ecommerce.payment.event.PaymentCompletedEvent;
import com.ecommerce.payment.event.PaymentFailedEvent;
import com.ecommerce.payment.event.PaymentRefundedEvent;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.apache.kafka.common.serialization.Serializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentEventProducerTest {

    @Mock KafkaTemplate<String, Object> kafkaTemplate;
    @InjectMocks PaymentEventProducer producer;

    @Test
    void publishCompleted_sendsToPaymentCompletedKeyedByOrderId() {
        PaymentCompletedEvent e = PaymentCompletedEvent.builder().paymentId(1L).orderId(42L).build();

        producer.publishCompleted(e);

        verify(kafkaTemplate).send("payment.completed", "42", e);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void publishFailed_sendsToPaymentFailedKeyedByOrderId() {
        PaymentFailedEvent e = PaymentFailedEvent.builder().paymentId(1L).orderId(43L).build();

        producer.publishFailed(e);

        verify(kafkaTemplate).send("payment.failed", "43", e);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    @Test
    void publishRefunded_sendsToPaymentRefundedKeyedByOrderId() {
        PaymentRefundedEvent e = PaymentRefundedEvent.builder().paymentId(1L).orderId(44L).build();

        producer.publishRefunded(e);

        verify(kafkaTemplate).send("payment.refunded", "44", e);
        verifyNoMoreInteractions(kafkaTemplate);
    }

    /**
     * The value serializer the production ProducerFactory hands to the Kafka client: java.time as
     * numeric arrays (what every consumer and the pacts expect), BigDecimal as a JSON number, and no
     * type-info headers.
     */
    @Test
    void productionValueSerializer_writesLocalDateTimeAsIntArray() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:0");
        ProducerFactory<String, Object> pf = config.producerFactory();
        Serializer<Object> serializer = pf.getValueSerializer();
        assertThat(serializer).isNotNull();

        PaymentCompletedEvent e = PaymentCompletedEvent.builder().paymentId(1L).orderId(42L).customerId("c-1")
                .amount(new BigDecimal("99.99")).transactionId("TXN-1")
                .completedAt(LocalDateTime.of(2026, 10, 29, 13, 45, 7, 123_000_000)).build();
        RecordHeaders headers = new RecordHeaders();
        byte[] bytes = serializer.serialize("payment.completed", headers, e);

        JsonNode json = JsonMapper.builder().build().readTree(new String(bytes, StandardCharsets.UTF_8));
        assertThat(json.get("completedAt").isArray()).isTrue();
        assertThat(json.get("completedAt").toString()).isEqualTo("[2026,10,29,13,45,7,123000000]");
        assertThat(json.get("amount").isNumber()).isTrue();
        assertThat(json.get("amount").decimalValue()).isEqualByComparingTo("99.99");
        assertThat(json.get("orderId").asLong()).isEqualTo(42L);
        assertThat(headers.toArray()).as("no __TypeId__ headers").isEmpty();
    }
}
