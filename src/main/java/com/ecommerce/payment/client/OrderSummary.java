package com.ecommerce.payment.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;

/** The parts of order-service's OrderDTO that payment-service reads. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderSummary(Long orderId, String customerId, BigDecimal totalAmount, String status) {
}
