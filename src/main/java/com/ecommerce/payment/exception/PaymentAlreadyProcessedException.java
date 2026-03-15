package com.ecommerce.payment.exception;
public class PaymentAlreadyProcessedException extends RuntimeException {
    public PaymentAlreadyProcessedException(String msg) { super(msg); }
}
