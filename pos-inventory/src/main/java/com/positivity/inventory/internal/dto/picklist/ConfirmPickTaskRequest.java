package com.positivity.inventory.internal.dto.picklist;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(
        description =
                "Request to confirm a pick task by recording the scanned SKU, scanned location, and quantity picked")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmPickTaskRequest {

    @Schema(
            description = "Identifier of the SKU scanned by the picker",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID scannedSkuId;

    @Schema(
            description = "Identifier of the bin or shelf location scanned during the pick",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID scannedLocationId;

    @Schema(
            description = "Quantity of units actually picked at the scanned location",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull
    @Positive
    private Integer quantityPicked;

    @Schema(
            description = "Lot number the units were picked from. Required (422 LOT_NUMBER_REQUIRED) when the SKU"
                    + " is LOT-tracked; must reference an existing (422 LOT_UNKNOWN) ACTIVE (422 LOT_NOT_AVAILABLE)"
                    + " lot. May differ from the task's advisory suggestedLotNumber. Ignored for untracked SKUs",
            example = "LOT-2026-A",
            requiredMode = NOT_REQUIRED)
    @Size(max = 128)
    private String lotNumber;

    /** Pre-E2 arity kept for existing callers/tests: no lot number keyed. */
    public ConfirmPickTaskRequest(UUID scannedSkuId, UUID scannedLocationId, Integer quantityPicked) {
        this(scannedSkuId, scannedLocationId, quantityPicked, null);
    }
}
