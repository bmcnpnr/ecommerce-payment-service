package com.ecommerce.payment.service;

import com.ecommerce.payment.client.OrderServiceClient;
import com.ecommerce.payment.client.OrderSummary;
import com.ecommerce.payment.dto.InitiatePaymentRequest;
import com.ecommerce.payment.exception.DependencyUnavailableException;
import com.ecommerce.payment.exception.ForbiddenException;
import com.ecommerce.payment.exception.InvalidPaymentStateException;
import com.ecommerce.payment.exception.OrderNotFoundException;
import com.ecommerce.payment.exception.OrderNotPayableException;
import com.ecommerce.payment.logging.LogThrottle;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * I-1: a payment is only accepted for an order that order-service says is CONFIRMED, belongs to the
 * payer and costs exactly the amount being paid. If order-service cannot be asked, the payment is refused.
 */
@Component
@RequiredArgsConstructor
public class OrderVerifier {

    private static final Logger log = LoggerFactory.getLogger(OrderVerifier.class);

    /** While order-service is down every payment attempt lands here: one line per kind per 10 s, not one per request. */
    private static final LogThrottle LOOKUP_FAILURE_LOG = new LogThrottle(10, TimeUnit.SECONDS);

    private final OrderServiceClient orderServiceClient;

    public void verifyPayable(InitiatePaymentRequest request) {
        OrderSummary order = fetch(request.getOrderId());
        if (!"CONFIRMED".equals(order.status())) {
            throw new OrderNotPayableException("Order " + request.getOrderId() + " is not awaiting payment");
        }
        if (order.customerId() == null || !order.customerId().equals(request.getCustomerId())) {
            // The payer is not the order's owner.
            throw new ForbiddenException();
        }
        if (order.totalAmount() == null || order.totalAmount().compareTo(request.getAmount()) != 0) {
            throw new InvalidPaymentStateException("Amount does not match the order total");
        }
    }

    private OrderSummary fetch(Long orderId) {
        OrderSummary order;
        try {
            order = orderServiceClient.getOrder(orderId);
        } catch (FeignException.NotFound e) {
            throw new OrderNotFoundException(orderId);
        } catch (RuntimeException e) {
            // Connect failure, timeout, no instance in the registry, open breaker, 5xx, undecodable body: never accept blind.
            // An operating condition, not a bug: one WARN per kind of failure per window, no stack trace.
            long suppressed = LOOKUP_FAILURE_LOG.acquire(e.getClass().getName());
            if (suppressed >= 0) {
                log.warn("order-service lookup failed for orderId={}: {}{}", orderId, e.getClass().getSimpleName(),
                        LogThrottle.suppressedSuffix(suppressed));
            }
            throw new DependencyUnavailableException("order-service unavailable", e);
        }
        if (order == null) {
            throw new DependencyUnavailableException("order-service returned no order", null);
        }
        return order;
    }
}
