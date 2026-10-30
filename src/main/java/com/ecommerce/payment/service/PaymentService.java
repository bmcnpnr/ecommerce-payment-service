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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

@Service @RequiredArgsConstructor
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    static final int MAX_PAGE_SIZE = 100;

    /**
     * Answered (409) to anyone who tries to pay for an order whose payment was declined. Fixed text: the web
     * frontend shows the same sentence when it sees the decline itself, so the two never disagree.
     */
    public static final String DECLINED_ORDER_MESSAGE =
            "The payment for this order was declined and the order is cancelled. Place a new order to try again.";

    private final PaymentRepository paymentRepository;
    private final PaymentEventProducer eventProducer;

    @Transactional
    public PaymentDTO initiatePayment(InitiatePaymentRequest request) {
        PaymentMethod method;
        try {
            method = PaymentMethod.valueOf(request.getMethod());
        } catch (IllegalArgumentException e) {
            throw new InvalidPaymentMethodException("Unsupported payment method: " + request.getMethod());
        }

        // payments.order_id is unique: a new attempt for an unpaid order reuses the existing row, but only
        // for the customer who owns it and only while it is still PENDING (a /process call that never happened
        // or timed out). A declined payment (FAILED) is final for the order.
        Payment payment = paymentRepository.findByOrderId(request.getOrderId()).orElse(null);
        if (payment != null) {
            if (payment.getStatus() == PaymentStatus.COMPLETED) {
                throw new PaymentAlreadyProcessedException("Payment already completed for order: " + request.getOrderId());
            }
            if (!payment.getCustomerId().equals(request.getCustomerId())) {
                // Never let a second caller rewrite someone else's payment (S-75). Before the FAILED check below, so
                // that nobody but the owner learns that the payment was declined.
                throw new PaymentAlreadyProcessedException("A payment already exists for order: " + request.getOrderId());
            }
            if (payment.getStatus() == PaymentStatus.FAILED) {
                // A decline ends the order: order-service cancels it, and gives its stock back, when it consumes
                // payment.failed. Until that event has been consumed (a second normally, unbounded while Kafka is
                // slow or down) the order still reads CONFIRMED, so a retry would pass the order check and be
                // charged; the earlier event would then cancel the order and the later payment.completed would
                // find it cancelled, with the money taken and the stock possibly sold. The two events travel on
                // different topics, so nothing orders them: the only safe rule is that FAILED is terminal.
                throw new PaymentAlreadyProcessedException(DECLINED_ORDER_MESSAGE);
            }
            if (payment.getStatus() != PaymentStatus.PENDING) {
                // REFUNDED must stay refunded (S-76); PROCESSING is mid-flight.
                throw new PaymentAlreadyProcessedException("Payment for order " + request.getOrderId()
                        + " is in status " + payment.getStatus() + " and cannot be initiated again");
            }
            payment.setTransactionId(null);
            payment.setFailureReason(null);
            payment.setUpdatedAt(LocalDateTime.now());
        } else {
            payment = new Payment();
            payment.setOrderId(request.getOrderId());
        }
        payment.setCustomerId(request.getCustomerId());
        payment.setAmount(request.getAmount());
        payment.setCurrency(request.getCurrency() != null ? request.getCurrency() : "USD");
        payment.setMethod(method);
        payment.setCardLastFour(request.getCardLastFour());
        payment.setStatus(PaymentStatus.PENDING);

        try {
            Payment saved = paymentRepository.save(payment);
            paymentRepository.flush();
            return toDTO(saved);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException e) {
            // The other side of a concurrent first initiate (unique order_id) or re-initiate (version) won.
            throw new PaymentAlreadyProcessedException("A payment is already being created for order: " + request.getOrderId());
        }
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
        claim();

        // Simulate payment gateway: 80% success rate
        boolean success = ThreadLocalRandom.current().nextInt(10) < 8;

        if (success) {
            payment.setStatus(PaymentStatus.COMPLETED);
            payment.setTransactionId(newTransactionId());
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

    /** One page of the customer's payments, newest first; {@code size} is capped at {@value #MAX_PAGE_SIZE}. */
    @Transactional(readOnly = true)
    public List<PaymentDTO> getPaymentsByCustomer(String customerId, int page, int size) {
        PageRequest pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "id"));
        return paymentRepository.findByCustomerId(customerId, pageable).stream().map(this::toDTO).collect(Collectors.toList());
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
        claim();

        eventProducer.publishRefunded(PaymentRefundedEvent.builder()
                .paymentId(payment.getId()).orderId(payment.getOrderId())
                .customerId(payment.getCustomerId()).amount(payment.getAmount())
                .refundedAt(LocalDateTime.now()).build());

        return toDTO(payment);
    }

    /**
     * 64 random bits. The column is unique and a collision would fail the commit of a payment that was just
     * approved; with the 32 bits used before, that starts to happen after a few tens of thousands of payments.
     */
    static String newTransactionId() {
        return "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
    }

    /**
     * Flushes the status change now. The row carries a version, so when two requests race on one
     * payment the loser's UPDATE finds a newer version, fails here, and is answered 409 - before
     * it can run the gateway or publish an event (S-77).
     */
    private void claim() {
        try {
            paymentRepository.flush();
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new PaymentAlreadyProcessedException("Payment is being processed by another request");
        }
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
