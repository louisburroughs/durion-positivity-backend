package com.positivity.inventory.internal.dto.cyclecount.tolerance;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to create a cycle-count tolerance configuration (ADR-0055 stage 4, #1416; issue #1414
 * owner spec).
 *
 * <p>{@code productId} and {@code storageLocation} are both optional; the pair together define
 * the scope this row applies to — omitting one broadens the scope, omitting both declares the
 * platform-wide default. At least one of {@code absoluteTolerance}/{@code percentageTolerance}
 * should normally be supplied, but both may be omitted to explicitly declare zero tolerance at a
 * scope that would otherwise inherit a looser default from a broader row.
 */
@Schema(description = "Request to create a tolerance configuration for cycle-count reconciliation")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateCycleCountToleranceRequest {

    @Schema(
            description = "Catalog product this tolerance scopes to. Omit for a product-agnostic (location-only"
                    + " or global) row.",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID productId;

    @Schema(
            description = "Storage location this tolerance scopes to, matching the value cycle-count tasks carry"
                    + " as binLocation. Omit for a location-agnostic (product-only or global) row.",
            example = "TANK-04",
            requiredMode = NOT_REQUIRED)
    private String storageLocation;

    @Schema(
            description = "Maximum acceptable absolute variance, in the product's base UoM",
            example = "50",
            requiredMode = NOT_REQUIRED)
    @DecimalMin(value = "0", message = "Absolute tolerance must be zero or positive")
    private BigDecimal absoluteTolerance;

    @Schema(
            description = "Maximum acceptable variance as a percentage of book quantity (e.g. 1.5 = 1.5%)",
            example = "1.5",
            requiredMode = NOT_REQUIRED)
    @DecimalMin(value = "0", message = "Percentage tolerance must be zero or positive")
    private BigDecimal percentageTolerance;

    @Schema(
            description = "Identifier of the user creating this configuration",
            example = "user-10042",
            requiredMode = REQUIRED)
    @NotBlank(message = "Created by user ID is required")
    private String createdBy;
}
