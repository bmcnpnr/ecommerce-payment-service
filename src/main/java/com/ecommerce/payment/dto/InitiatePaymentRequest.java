package com.ecommerce.payment.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class InitiatePaymentRequest {
    @NotNull private Long orderId;
    @NotBlank private String customerId;
    @NotNull @DecimalMin("0.01") private BigDecimal amount;
    @Size(max = 3) private String currency;
    @NotBlank private String method;
    @Size(max = 4) private String cardLastFour;
}
