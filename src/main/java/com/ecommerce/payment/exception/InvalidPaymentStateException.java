package com.ecommerce.payment.exception;
public class InvalidPaymentStateException extends RuntimeException {
    public InvalidPaymentStateException(String msg) { super(msg); }
}
