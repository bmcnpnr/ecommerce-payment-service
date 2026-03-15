package com.ecommerce.payment.exception;
public class PaymentNotFoundException extends RuntimeException {
    public PaymentNotFoundException(String msg) { super(msg); }
}
