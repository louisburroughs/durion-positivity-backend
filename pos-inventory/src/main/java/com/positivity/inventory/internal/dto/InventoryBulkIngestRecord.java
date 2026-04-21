package com.positivity.inventory.internal.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
public class InventoryBulkIngestRecord {

    @NotBlank
    private String sku;

    private UUID locationId;

    @NotNull
    @Min(0)
    private Integer quantity;

    private String reasonCode;

    private String unitOfMeasure;
}
