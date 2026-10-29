package com.ecommerce.payment.exception;
public class InvalidPaymentMethodException extends RuntimeException {
    public InvalidPaymentMethodException(String msg) { super(msg); }
}
