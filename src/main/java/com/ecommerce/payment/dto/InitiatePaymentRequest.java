package com.ecommerce.payment.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;

@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class InitiatePaymentRequest {
    @NotNull @Positive
    private Long orderId;

    // Same bound as the column; control characters (NUL, CR, LF) have no place in an identifier.
    @NotBlank @Size(max = 255) @Pattern(regexp = "^[^\\p{Cntrl}]*$", message = "must not contain control characters")
    private String customerId;

    // NUMERIC(19,4) holds 15 integer digits, but money has cents: at most two decimals.
    @NotNull @DecimalMin("0.01") @Digits(integer = 15, fraction = 2)
    private BigDecimal amount;

    @Pattern(regexp = "^[A-Z]{3}$", message = "must be a three-letter upper-case currency code")
    private String currency;

    @NotBlank @Size(max = 30)
    private String method;

    @Pattern(regexp = "^[0-9]{4}$", message = "must be exactly four digits")
    private String cardLastFour;
}
