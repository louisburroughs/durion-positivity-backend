package com.positivity.accounting.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to apply an open customer credit against an invoice (issue #992). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Apply an open customer credit to an outstanding invoice")
public class CustomerCreditApplicationRequest {

    @NotBlank
    @Size(max = 100)
    @Schema(
            description =
                    "Caller-supplied idempotency key. Replaying the same key returns the original application instead of relieving the credit liability twice",
            example = "credit-apply-2026-07-22-0001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String requestId;

    @NotNull
    @Schema(
            description =
                    "Invoice the credit settles. Must exist in the invoice replica, be AR-eligible, and have a positive balance",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID invoiceId;

    @NotNull
    @DecimalMin(value = "0.01", message = "Applied amount must be positive")
    @Digits(integer = 15, fraction = 4)
    @Schema(
            description =
                    "Amount to apply. Must not exceed the credit's open amount, nor the invoice's remaining balance",
            example = "50.00",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;
}
