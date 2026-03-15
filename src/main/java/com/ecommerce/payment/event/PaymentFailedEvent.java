package com.ecommerce.payment.event;

import lombok.*;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentFailedEvent {
    private Long paymentId;
    private Long orderId;
    private String customerId;
    private String failureReason;
    private LocalDateTime failedAt;
}
