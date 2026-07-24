package com.positivity.inventory.internal.dto.revaluation;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for submitting a manual cost revaluation (odoo-parity J4, issue #1054).
 *
 * <p>Supply exactly one of {@code newUnitCost} (the absolute corrected unit cost) or {@code costDelta}
 * (a signed adjustment applied to the SKU's current cost). The revaluation targets whichever cost the
 * SKU's resolved costing method uses — the standard price under STANDARD, the running average under
 * AVERAGE.
 */
@Schema(description = "Request to manually revalue a SKU's inventory unit cost")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateRevaluationRequest {

    @Schema(description = "SKU / stock item identifier to revalue", example = "OIL-5W30-5QT", requiredMode = REQUIRED)
    @NotBlank(message = "Stock item ID is required")
    private String stockItemId;

    @Schema(
            description = "Absolute corrected unit cost. Supply exactly one of newUnitCost or costDelta."
                    + " Must be zero or positive",
            example = "5.5000",
            requiredMode = NOT_REQUIRED)
    private BigDecimal newUnitCost;

    @Schema(
            description = "Signed adjustment applied to the SKU's current cost (may be negative for a write-down)."
                    + " Supply exactly one of newUnitCost or costDelta",
            example = "-0.7500",
            requiredMode = NOT_REQUIRED)
    private BigDecimal costDelta;

    @Schema(
            description = "Justification for the correction; recorded in the audit log",
            example = "Q3 physical recount valuation correction",
            requiredMode = REQUIRED)
    @NotBlank(message = "Reason is required")
    @Size(max = 1000, message = "Reason must not exceed 1000 characters")
    private String reason;
}
