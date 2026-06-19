package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

@Data
@Schema(description = "Catalog item upsert request. Fields are type-dependent.")
public class CatalogItemRequestDto {

    @Schema(description = "Item name", example = "Heavy Duty Wrench", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(description = "Short description", example = "Adjustable steel wrench", requiredMode = NOT_REQUIRED)
    private String shortDescription;

    @Schema(
            description = "Long description",
            example = "Forged steel adjustable wrench with cushioned grip",
            requiredMode = NOT_REQUIRED)
    private String longDescription;

    @Schema(
            description = "Product image URLs for product type",
            example = "[\"https://cdn.example.com/img/wrench.jpg\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> images;

    @Schema(description = "Manufacturer part number for product type", example = "MPN-9981", requiredMode = NOT_REQUIRED)
    private String manufacturerPartNumber;

    @Schema(
            description = "Manufacturer identifier for product type",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = NOT_REQUIRED)
    private String manufacturerId;

    @Schema(description = "Manufacturer name for product type", example = "AcmePro", requiredMode = NOT_REQUIRED)
    private String manufacturerName;

    @Schema(
            description = "Manufacturer warranty for product type",
            example = "2 years limited",
            requiredMode = NOT_REQUIRED)
    private String manufacturerWarranty;

    @Schema(description = "Manufacturer brand for product type", example = "AcmePro", requiredMode = NOT_REQUIRED)
    private String manufacturerBrand;

    @Schema(description = "Country of origin for product type", example = "US", requiredMode = NOT_REQUIRED)
    private String countryOfOrigin;

    @Schema(description = "SKU for product type", example = "SKU-12345", requiredMode = NOT_REQUIRED)
    private String sku;

    @Schema(description = "Product code for product type", example = "012345678905", requiredMode = NOT_REQUIRED)
    private String productCode;

    @Schema(description = "Product type", example = "PHYSICAL", requiredMode = NOT_REQUIRED)
    private String type;

    @Schema(description = "Material for product type", example = "Steel", requiredMode = NOT_REQUIRED)
    private String material;

    @Schema(description = "Color for product type", example = "Black", requiredMode = NOT_REQUIRED)
    private String color;

    @Schema(description = "Warranty for product type", example = "1 year", requiredMode = NOT_REQUIRED)
    private String warranty;

    @Schema(
            description = "Detailed specifications JSON for product type",
            example = "{\"weightKg\":1.2}",
            requiredMode = NOT_REQUIRED)
    private String specifications;
}
