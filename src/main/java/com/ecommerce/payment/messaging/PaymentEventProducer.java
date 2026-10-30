package com.ecommerce.payment.messaging;

import com.ecommerce.payment.config.KafkaProducerConfig;
import com.ecommerce.payment.event.*;
import com.ecommerce.payment.outbox.OutboxEvent;
import com.ecommerce.payment.outbox.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;

/**
 * Announces payment results. The record is not sent here: it is written to the outbox table in the caller's
 * transaction, so it exists exactly when the payment change commits, and
 * {@link com.ecommerce.payment.outbox.OutboxRelay} publishes it to Kafka afterwards (R-2). The JSON is the
 * same the old {@code JacksonJsonSerializer} wrote: java.time as numeric arrays, no type headers.
 */
@Component @RequiredArgsConstructor
public class PaymentEventProducer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventProducer.class);
    private static final JsonMapper EVENT_MAPPER = KafkaProducerConfig.eventJsonMapper();

    private final OutboxEventRepository outbox;

    @Transactional
    public void publishCompleted(PaymentCompletedEvent e) {
        enqueue("payment.completed", e.getOrderId().toString(), e);
    }

    @Transactional
    public void publishFailed(PaymentFailedEvent e) {
        enqueue("payment.failed", e.getOrderId().toString(), e);
    }

    @Transactional
    public void publishRefunded(PaymentRefundedEvent e) {
        enqueue("payment.refunded", e.getOrderId().toString(), e);
    }

    private void enqueue(String topic, String key, Object event) {
        outbox.save(OutboxEvent.builder()
                .topic(topic)
                .eventKey(key)
                .payload(EVENT_MAPPER.writeValueAsString(event))
                .createdAt(LocalDateTime.now())
                .build());
        log.debug("Queued {} for orderId={}", topic, key);
    }
}
