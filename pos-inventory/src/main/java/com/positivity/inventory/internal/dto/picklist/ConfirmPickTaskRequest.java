package com.positivity.inventory.internal.dto.picklist;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Request to confirm a pick task by recording the scanned SKU, scanned location, and quantity picked")
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
}
