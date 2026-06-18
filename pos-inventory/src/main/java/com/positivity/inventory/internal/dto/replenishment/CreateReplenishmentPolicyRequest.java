package com.positivity.inventory.internal.dto.replenishment;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
@Schema(description = "Request to create a replenishment policy defining min/max stock thresholds for an item at a location")
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

    @AssertTrue(message = "minimumQuantity must be less than maximumQuantity")
    public boolean isMinimumLessThanMaximum() {
        if (minimumQuantity == null || maximumQuantity == null) return true;
        return minimumQuantity < maximumQuantity;
    }
}
