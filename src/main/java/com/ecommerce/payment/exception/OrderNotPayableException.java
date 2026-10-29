package com.ecommerce.payment.exception;

/** The order exists but cannot be paid now (not CONFIRMED) (409). */
public class OrderNotPayableException extends RuntimeException {
    public OrderNotPayableException(String message) { super(message); }
}
