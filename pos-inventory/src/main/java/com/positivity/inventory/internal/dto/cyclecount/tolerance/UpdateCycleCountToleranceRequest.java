package com.positivity.inventory.internal.dto.cyclecount.tolerance;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to replace a cycle-count tolerance's bounds and active state (PUT semantics — both
 * bound fields are set verbatim from the request, null included). The scope (product, storage
 * location) is immutable once created — recreate the row to change scope.
 */
@Schema(description = "Request to update a tolerance configuration's bounds or active state")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UpdateCycleCountToleranceRequest {

    @Schema(
            description = "Maximum acceptable absolute variance, in the product's base UoM. Null clears it.",
            example = "50",
            requiredMode = NOT_REQUIRED)
    @DecimalMin(value = "0", message = "Absolute tolerance must be zero or positive")
    private BigDecimal absoluteTolerance;

    @Schema(
            description = "Maximum acceptable variance as a percentage of book quantity",
            example = "1.5",
            requiredMode = NOT_REQUIRED)
    @DecimalMin(value = "0", message = "Percentage tolerance must be zero or positive")
    private BigDecimal percentageTolerance;

    @Schema(description = "Whether this configuration is in effect", example = "true", requiredMode = REQUIRED)
    @NotNull(message = "Active is required")
    private Boolean active;
}
