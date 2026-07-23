package com.positivity.inventory.internal.dto.replenishment;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.ReplenishmentSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Full-replace update of a replenishment policy's tunable fields (odoo-parity F3, issue
 * #1041). PUT semantics: omitted optional fields are cleared / reset to their defaults
 * ({@code orderMultiple} and {@code leadTimeDaysOverride} to none, {@code
 * preferredSourceType} to EITHER, {@code active} to true). The policy's identity
 * (location, SKU) and its snooze state are not updatable here — snooze is managed by the
 * dedicated snooze endpoint.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description = "Full-replace update of a replenishment policy's thresholds and tuning fields. Omitted optional"
                + " fields are cleared or reset to defaults; location, SKU, and snooze state are not updatable here.")
public class UpdateReplenishmentPolicyRequest {

    @Schema(
            description = "Minimum on-hand quantity that triggers replenishment when reached",
            example = "5",
            requiredMode = REQUIRED)
    @NotNull
    @Min(0)
    private Integer minimumQuantity;

    @Schema(
            description = "Maximum on-hand quantity replenishment aims to restock up to",
            example = "50",
            requiredMode = REQUIRED)
    @NotNull
    @Min(1)
    private Integer maximumQuantity;

    @Schema(
            description = "Round the computed replenishment quantity up to the nearest multiple of this value;"
                    + " omit to clear (no rounding)",
            example = "6",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer orderMultiple;

    @Schema(
            description =
                    "Per-policy lead-time override in days; omit to clear (vendor-feed/default lead time" + " applies)",
            example = "3",
            requiredMode = NOT_REQUIRED)
    @Min(0)
    private Integer leadTimeDaysOverride;

    @Schema(
            description =
                    "Preferred sourcing channel for replenishing this policy's pick face;" + " omit to reset to EITHER",
            example = "EITHER",
            requiredMode = NOT_REQUIRED)
    private ReplenishmentSourceType preferredSourceType;

    @Schema(
            description = "Whether the policy participates in replenishment evaluation; omit to reset to true",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean active;

    @AssertTrue(message = "minimumQuantity must be less than maximumQuantity")
    public boolean isMinimumLessThanMaximum() {
        if (minimumQuantity == null || maximumQuantity == null) return true;
        return minimumQuantity < maximumQuantity;
    }
}
