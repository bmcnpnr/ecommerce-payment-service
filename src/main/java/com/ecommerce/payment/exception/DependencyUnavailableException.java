package com.ecommerce.payment.exception;

/** A service this one needs to decide (order-service) cannot be reached: the request is refused, never accepted blind (503). */
public class DependencyUnavailableException extends RuntimeException {
    public DependencyUnavailableException(String message, Throwable cause) { super(message, cause); }
}
