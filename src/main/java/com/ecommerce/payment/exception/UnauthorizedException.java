package com.ecommerce.payment.exception;

/** No usable identity on the request (401). */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException() { super("Authentication required"); }
}
