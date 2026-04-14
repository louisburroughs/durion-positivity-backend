package com.positivity.inventory.internal.dto.picklist;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmPickTaskRequest {

    @NotNull
    private UUID scannedSkuId;

    @NotNull
    private UUID scannedLocationId;

    @NotNull
    @Positive
    private Integer quantityPicked;
}
