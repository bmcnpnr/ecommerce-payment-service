package com.ecommerce.payment.exception;

/** order-service does not know the order a payment refers to (404). */
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(Long orderId) { super("Order not found: " + orderId); }
}
