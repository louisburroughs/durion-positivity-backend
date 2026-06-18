package com.positivity.inventory.internal.dto.replenishment;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Representation of a replenishment policy defining min/max stock thresholds for an item at a location")
public class ReplenishmentPolicyResponse {

    @Schema(
            description = "Unique identifier of the replenishment policy",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private String policyId;

    @Schema(
            description = "Identifier of the location the replenishment policy applies to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID locationId;

    @Schema(
            description = "SKU of the item the replenishment policy applies to",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String itemSKU;

    @Schema(
            description = "Minimum on-hand quantity that triggers replenishment when reached",
            example = "5",
            requiredMode = REQUIRED)
    private int minimumQuantity;

    @Schema(
            description = "Maximum on-hand quantity replenishment aims to restock up to",
            example = "50",
            requiredMode = REQUIRED)
    private int maximumQuantity;

    @Schema(
            description = "Timestamp at which the replenishment policy was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private String createdAt;
}
