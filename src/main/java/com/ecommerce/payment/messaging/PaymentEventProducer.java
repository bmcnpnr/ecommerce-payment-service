package com.ecommerce.payment.messaging;

import com.ecommerce.payment.event.*;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component @RequiredArgsConstructor
public class PaymentEventProducer {
    private static final Logger log = LoggerFactory.getLogger(PaymentEventProducer.class);
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishCompleted(PaymentCompletedEvent e) {
        log.info("Publishing payment.completed for orderId={}", e.getOrderId());
        kafkaTemplate.send("payment.completed", e.getOrderId().toString(), e);
    }

    public void publishFailed(PaymentFailedEvent e) {
        log.info("Publishing payment.failed for orderId={}", e.getOrderId());
        kafkaTemplate.send("payment.failed", e.getOrderId().toString(), e);
    }

    public void publishRefunded(PaymentRefundedEvent e) {
        log.info("Publishing payment.refunded for orderId={}", e.getOrderId());
        kafkaTemplate.send("payment.refunded", e.getOrderId().toString(), e);
    }
}
