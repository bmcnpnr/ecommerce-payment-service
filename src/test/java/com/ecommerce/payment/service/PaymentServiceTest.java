package com.ecommerce.payment.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.junit.jupiter.MockitoExtension;

import static com.ecommerce.payment.service.PaymentService.HELLO_FROM_PAYMENT_SERVICE;
import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockitoExtension.class)
public class PaymentServiceTest {

    @InjectMocks
    private PaymentService paymentService;

    @Test
    void testGetHelloMessage() {
        // when
        var result = paymentService.getHelloMessage();

        // then
        assertEquals(HELLO_FROM_PAYMENT_SERVICE, result);
    }
}
