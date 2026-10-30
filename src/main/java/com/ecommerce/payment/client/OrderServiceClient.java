package com.ecommerce.payment.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * order-service, resolved through Eureka. Internal endpoint: the gateway never routes
 * /internal/**, so this is the only way in and it needs no identity.
 * Timeouts are set in application.yaml (spring.cloud.openfeign.client.config.default); the circuit breaker is
 * {@code resilience4j.circuitbreaker.instances.order-service}.
 */
@FeignClient(name = "order-service", fallbackFactory = OrderServiceClientFallbackFactory.class)
public interface OrderServiceClient {

    @GetMapping("/internal/v1/orders/{id}")
    OrderSummary getOrder(@PathVariable("id") Long orderId);
}
