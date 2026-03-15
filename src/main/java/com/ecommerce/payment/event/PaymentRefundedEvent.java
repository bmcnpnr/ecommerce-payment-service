package com.ecommerce.payment.event;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentRefundedEvent {
    private Long paymentId;
    private Long orderId;
    private String customerId;
    private BigDecimal amount;
    private LocalDateTime refundedAt;
}
