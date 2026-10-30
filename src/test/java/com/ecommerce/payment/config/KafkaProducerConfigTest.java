package com.ecommerce.payment.config;

import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaProducerConfigTest {

    /**
     * While the broker is down the outbox relay retries with back-off and logs one throttled WARN; the template's
     * default LoggingProducerListener would add an ERROR with a stack trace per failed send. Same as order-service.
     */
    @Test
    void aFailedSendIsReportedByTheRelayOnly_notByTheTemplate() {
        KafkaProducerConfig config = new KafkaProducerConfig();
        ReflectionTestUtils.setField(config, "bootstrapServers", "localhost:9092");

        KafkaTemplate<String, String> template = config.kafkaTemplate();

        assertThat(ReflectionTestUtils.getField(template, "producerListener")).isNotInstanceOf(LoggingProducerListener.class);
    }
}
