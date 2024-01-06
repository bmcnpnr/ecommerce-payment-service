package com.ecommerce.payment.service;

import org.springframework.stereotype.Service;

@Service
public class PaymentService {

    public static final String HELLO_FROM_PAYMENT_SERVICE = "Hello from Payment Service!";

    public String getHelloMessage() {
        return HELLO_FROM_PAYMENT_SERVICE;
    }
}
