package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Schema(description = "Single record within a bulk inventory ingest request")
@Data
public class InventoryBulkIngestRecord {

    @Schema(
            description = "Stock-keeping unit of the product being ingested",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotBlank
    private String sku;

    @Schema(
            description = "Identifier of the location the quantity applies to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Quantity to ingest for the product (non-negative)",
            example = "120",
            requiredMode = REQUIRED)
    @NotNull
    @Min(0)
    private Integer quantity;

    @Schema(
            description = "Optional reason code explaining the ingest",
            example = "CYCLE_COUNT",
            requiredMode = NOT_REQUIRED)
    private String reasonCode;

    @Schema(
            description = "Optional unit of measure code for the quantity (e.g. EACH, KG)",
            example = "EACH",
            requiredMode = NOT_REQUIRED)
    private String unitOfMeasure;
}
