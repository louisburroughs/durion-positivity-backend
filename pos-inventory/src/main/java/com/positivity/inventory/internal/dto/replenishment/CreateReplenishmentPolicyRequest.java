package com.positivity.inventory.internal.dto.replenishment;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.ReplenishmentSourceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(
        description =
                "Request to create a replenishment policy defining min/max stock thresholds for an item at a location")
public class CreateReplenishmentPolicyRequest {

    @Schema(
            description = "Identifier of the location the replenishment policy applies to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "SKU of the item the replenishment policy applies to",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotBlank
    private String itemSKU;

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
            description = "Round the computed replenishment quantity up to the nearest multiple of this value."
                    + " When omitted, defaults to the vendor-feed pack size for the SKU when one is known"
                    + " (greater than 1); otherwise no rounding is applied.",
            example = "6",
            requiredMode = NOT_REQUIRED)
    @Min(1)
    private Integer orderMultiple;

    @Schema(
            description = "Per-policy lead-time override in days; takes precedence over vendor-feed lead-time"
                    + " estimates and the configured default",
            example = "3",
            requiredMode = NOT_REQUIRED)
    @Min(0)
    private Integer leadTimeDaysOverride;

    @Schema(
            description =
                    "Preferred sourcing channel for replenishing this policy's pick face;" + " defaults to EITHER",
            example = "EITHER",
            requiredMode = NOT_REQUIRED)
    private ReplenishmentSourceType preferredSourceType;

    @Schema(
            description = "Whether the policy participates in replenishment evaluation; defaults to true",
            example = "true",
            requiredMode = NOT_REQUIRED)
    private Boolean active;

    @AssertTrue(message = "minimumQuantity must be less than maximumQuantity")
    public boolean isMinimumLessThanMaximum() {
        if (minimumQuantity == null || maximumQuantity == null) return true;
        return minimumQuantity < maximumQuantity;
    }
}
