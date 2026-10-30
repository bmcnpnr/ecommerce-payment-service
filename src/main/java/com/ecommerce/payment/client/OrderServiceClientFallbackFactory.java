package com.ecommerce.payment.client;

import org.springframework.cloud.openfeign.FallbackFactory;
import org.springframework.stereotype.Component;

/**
 * The circuit breaker hands every failure to its fallback, a 404 from order-service included, and without
 * a fallback it would wrap them all in {@code NoFallbackAvailableException}, which {@code OrderVerifier}
 * could no longer tell from an outage. This one gives the original exception back untouched, so an unknown
 * order stays a 404 and an open breaker ({@code CallNotPermittedException}) is refused with a 503 like any
 * other order-service outage.
 *
 * <p>It logs nothing: during an outage it would run once per payment attempt. The breaker's transitions
 * are logged once ({@code CircuitBreakerTransitionLogger}) and its metrics count the calls.
 */
@Component
public class OrderServiceClientFallbackFactory implements FallbackFactory<OrderServiceClient> {

    @Override
    public OrderServiceClient create(Throwable cause) {
        return orderId -> {
            throw cause instanceof RuntimeException runtime ? runtime : new IllegalStateException(cause);
        };
    }
}
