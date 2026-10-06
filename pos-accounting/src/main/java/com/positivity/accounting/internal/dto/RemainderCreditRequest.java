package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Keep a payment's unapplied remainder as a customer credit (AD-003; CAP:550 S35, #2524;
 * SPEC-accounting-workspace §5.3 item 5). Idempotent on {@code requestId}; {@code expectedAmount}
 * guards against an application that intervened since the caller read the payment.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Keep a payment's unapplied remainder as a customer credit")
public class RemainderCreditRequest {

    /**
     * Idempotency key for this command. A replay with the same key returns the credit it issued;
     * the same key on another payment is refused.
     */
    @Schema(
            description = "Idempotency key for this command; a replay returns the credit it issued, the same key on"
                    + " another payment is refused with IDEMPOTENCY_CONFLICT",
            example = "remainder-2026-10-06-017",
            requiredMode = REQUIRED)
    @NotBlank(message = "requestId is required for idempotency")
    @Size(max = 100, message = "requestId must not exceed 100 characters")
    private String requestId;

    /**
     * The unapplied amount the caller saw on the payment; must equal it exactly or the command is
     * refused with PAYMENT_REMAINDER_CHANGED.
     */
    @Schema(
            description = "The payment's unapplied amount as the caller read it; must still match exactly",
            example = "12.50",
            requiredMode = REQUIRED)
    @NotNull(message = "expectedAmount is required")
    @DecimalMin(value = "0.01", message = "expectedAmount must be at least 0.01")
    @Digits(integer = 15, fraction = 4, message = "expectedAmount must have at most 4 decimal places")
    private BigDecimal expectedAmount;
}
