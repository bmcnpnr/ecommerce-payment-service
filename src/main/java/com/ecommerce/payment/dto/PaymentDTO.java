package com.ecommerce.payment.dto;

import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentDTO {
    private Long id;
    private Long orderId;
    private String customerId;
    private BigDecimal amount;
    private String currency;
    private String status;
    private String method;
    private String transactionId;
    private String failureReason;
    private String cardLastFour;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
