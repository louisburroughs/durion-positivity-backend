package com.positivity.accounting.internal.dto;

import com.positivity.accounting.internal.enums.BankAdjustmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A supported reconciliation adjustment type (Story F2, issue #965, decision D-6).
 * Served so the frontend never hardcodes the enum.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A supported reconciliation adjustment type")
public class AdjustmentTypeResponse {

    @Schema(description = "Adjustment type code", example = "BANK_FEE")
    private BankAdjustmentType code;

    public static AdjustmentTypeResponse from(BankAdjustmentType type) {
        return AdjustmentTypeResponse.builder().code(type).build();
    }
}
