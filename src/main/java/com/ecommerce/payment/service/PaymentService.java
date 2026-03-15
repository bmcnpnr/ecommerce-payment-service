package com.ecommerce.payment.service;

import com.ecommerce.payment.dto.*;
import com.ecommerce.payment.event.*;
import com.ecommerce.payment.exception.*;
import com.ecommerce.payment.messaging.PaymentEventProducer;
import com.ecommerce.payment.model.*;
import com.ecommerce.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer eventProducer;

    @Transactional
    public PaymentDTO initiatePayment(InitiatePaymentRequest request) {
        paymentRepository.findByOrderId(request.getOrderId()).ifPresent(existing -> {
            if (existing.getStatus() == PaymentStatus.COMPLETED) {
                throw new PaymentAlreadyProcessedException("Payment already completed for order: " + request.getOrderId());
            }
        });

        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .customerId(request.getCustomerId())
                .amount(request.getAmount())
                .currency(request.getCurrency() != null ? request.getCurrency() : "USD")
                .method(PaymentMethod.valueOf(request.getMethod()))
                .cardLastFour(request.getCardLastFour())
                .status(PaymentStatus.PENDING)
                .build();

        return toDTO(paymentRepository.save(payment));
    }

    @Transactional
    public PaymentDTO processPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));

        if (payment.getStatus() != PaymentStatus.PENDING) {
            throw new PaymentAlreadyProcessedException("Payment already in status: " + payment.getStatus());
        }

        payment.setStatus(PaymentStatus.PROCESSING);
        paymentRepository.save(payment);

        // Simulate payment gateway: 80% success rate
        boolean success = ThreadLocalRandom.current().nextInt(10) < 8;

        if (success) {
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setTransactionId("TXN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
            payment.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(payment);

            eventProducer.publishCompleted(PaymentCompletedEvent.builder()
                    .paymentId(payment.getId()).orderId(payment.getOrderId())
                    .customerId(payment.getCustomerId()).amount(payment.getAmount())
                    .transactionId(payment.getTransactionId()).completedAt(LocalDateTime.now())
                    .build());
            log.info("Payment {} completed for order {}", paymentId, payment.getOrderId());
        } else {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason("Payment declined by gateway");
            payment.setUpdatedAt(LocalDateTime.now());
            paymentRepository.save(payment);

            eventProducer.publishFailed(PaymentFailedEvent.builder()
                    .paymentId(payment.getId()).orderId(payment.getOrderId())
                    .customerId(payment.getCustomerId()).failureReason(payment.getFailureReason())
                    .failedAt(LocalDateTime.now()).build());
            log.info("Payment {} failed for order {}", paymentId, payment.getOrderId());
        }

        return toDTO(payment);
    }

    @Transactional(readOnly = true)
    public PaymentDTO getPaymentById(Long id) {
        return toDTO(paymentRepository.findById(id)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + id)));
    }

    @Transactional(readOnly = true)
    public PaymentDTO getPaymentByOrderId(Long orderId) {
        return toDTO(paymentRepository.findByOrderId(orderId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found for order: " + orderId)));
    }

    @Transactional(readOnly = true)
    public List<PaymentDTO> getPaymentsByCustomer(String customerId) {
        return paymentRepository.findByCustomerId(customerId).stream().map(this::toDTO).collect(Collectors.toList());
    }

    @Transactional
    public PaymentDTO refundPayment(Long paymentId) {
        Payment payment = paymentRepository.findById(paymentId)
                .orElseThrow(() -> new PaymentNotFoundException("Payment not found: " + paymentId));

        if (payment.getStatus() != PaymentStatus.COMPLETED) {
            throw new InvalidPaymentStateException("Can only refund COMPLETED payments. Current: " + payment.getStatus());
        }

        payment.setStatus(PaymentStatus.REFUNDED);
        payment.setUpdatedAt(LocalDateTime.now());
        paymentRepository.save(payment);

        eventProducer.publishRefunded(PaymentRefundedEvent.builder()
                .paymentId(payment.getId()).orderId(payment.getOrderId())
                .customerId(payment.getCustomerId()).amount(payment.getAmount())
                .refundedAt(LocalDateTime.now()).build());

        return toDTO(payment);
    }

    private PaymentDTO toDTO(Payment p) {
        return PaymentDTO.builder()
                .id(p.getId()).orderId(p.getOrderId()).customerId(p.getCustomerId())
                .amount(p.getAmount()).currency(p.getCurrency()).status(p.getStatus().name())
                .method(p.getMethod().name()).transactionId(p.getTransactionId())
                .failureReason(p.getFailureReason()).cardLastFour(p.getCardLastFour())
                .createdAt(p.getCreatedAt()).updatedAt(p.getUpdatedAt()).build();
    }
}
