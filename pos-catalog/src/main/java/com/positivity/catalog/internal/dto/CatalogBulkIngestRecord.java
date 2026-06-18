package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;
import lombok.Data;

@Data
@Schema(description = "Single record in a catalog bulk-ingest request")
public class CatalogBulkIngestRecord {

    @NotBlank
    @Schema(description = "Stock keeping unit for the record", example = "SKU-12345", requiredMode = REQUIRED)
    private String sku;

    @Schema(description = "Universal product code", example = "012345678905", requiredMode = NOT_REQUIRED)
    private String upc;

    @NotBlank
    @Schema(description = "Display name of the item", example = "Heavy Duty Wrench", requiredMode = REQUIRED)
    private String name;

    @Schema(description = "Long description of the item", example = "Forged steel adjustable wrench", requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(description = "Category name to associate the item with", example = "Tools", requiredMode = NOT_REQUIRED)
    private String categoryName;

    @Schema(description = "Subcategory name to associate the item with", example = "Hand Tools", requiredMode = NOT_REQUIRED)
    private String subcategoryName;

    @Schema(description = "List price for the item", example = "29.99", requiredMode = NOT_REQUIRED)
    private BigDecimal price;
}
