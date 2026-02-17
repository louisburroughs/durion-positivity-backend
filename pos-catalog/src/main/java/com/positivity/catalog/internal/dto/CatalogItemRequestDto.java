package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "Catalog item upsert request. Fields are type-dependent.")
public class CatalogItemRequestDto {

    @Schema(description = "Item name", example = "Heavy Duty Wrench")
    private String name;

    @Schema(description = "Short description")
    private String shortDescription;

    @Schema(description = "Long description")
    private String longDescription;

    @Schema(description = "Product image URLs for product type")
    private List<String> images;

    @Schema(description = "Manufacturer part number for product type")
    private String manufacturerPartNumber;

    @Schema(description = "Manufacturer identifier for product type")
    private String manufacturerId;

    @Schema(description = "Manufacturer name for product type")
    private String manufacturerName;

    @Schema(description = "Manufacturer warranty for product type")
    private String manufacturerWarranty;

    @Schema(description = "Manufacturer brand for product type")
    private String manufacturerBrand;

    @Schema(description = "Country of origin for product type")
    private String countryOfOrigin;

    @Schema(description = "SKU for product type")
    private String sku;

    @Schema(description = "Product code for product type")
    private String productCode;

    @Schema(description = "Product type", example = "PHYSICAL")
    private String type;

    @Schema(description = "Material for product type")
    private String material;

    @Schema(description = "Color for product type")
    private String color;

    @Schema(description = "Warranty for product type")
    private String warranty;

    @Schema(description = "Detailed specifications JSON for product type")
    private String specifications;
}
