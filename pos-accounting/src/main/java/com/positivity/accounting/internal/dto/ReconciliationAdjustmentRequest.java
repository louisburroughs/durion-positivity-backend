package com.positivity.accounting.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.accounting.internal.enums.BankAdjustmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to record a reconciliation adjustment (Story F2, issue #965, decision
 * D-6). Recording an adjustment posts a real balanced journal entry through the
 * accounting-period gate.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to record a reconciliation adjustment (posts a real JE)")
public class ReconciliationAdjustmentRequest {

    @NotNull(message = "type is required")
    @Schema(description = "Adjustment type (decision D-6)", example = "BANK_FEE", requiredMode = REQUIRED)
    private BankAdjustmentType type;

    @NotNull(message = "amount is required")
    @Digits(integer = 15, fraction = 4, message = "amount must have at most 15 integer and 4 fractional digits")
    @Schema(
            description = "Signed adjustment amount; positive increases the reconciled cash account",
            example = "-12.5000",
            requiredMode = REQUIRED)
    private BigDecimal amount;

    @Size(max = 500, message = "description must not exceed 500 characters")
    @Schema(description = "Optional description recorded on the adjustment", example = "Monthly account service charge")
    private String description;
}
