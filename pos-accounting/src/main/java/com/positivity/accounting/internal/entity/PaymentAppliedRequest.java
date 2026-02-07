package com.positivity.accounting.internal.entity;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for payment applied request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentAppliedRequest {

    @NotNull(message = "Invoice ID is required")
    private UUID invoiceId;

    @NotBlank(message = "Transaction reference is required")
    private String transactionReference;

    @NotNull(message = "Payment amount is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Payment amount must be positive")
    private BigDecimal paymentAmount;

    @NotNull(message = "Invoice total is required")
    @DecimalMin(value = "0.0", inclusive = false, message = "Invoice total must be positive")
    private BigDecimal invoiceTotal;

    @NotBlank(message = "Idempotency key is required")
    private String idempotencyKey;

    @Builder.Default
    private boolean paymentFailed = false;

}
