package com.ecommerce.payment.exception;

/** Authenticated, but neither the owner nor an admin (403). */
public class ForbiddenException extends RuntimeException {
    public ForbiddenException() { super("Access denied"); }
}
