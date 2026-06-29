package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.catalog.internal.entity.ProductCodeType;
import com.positivity.catalog.internal.entity.ProductLifecycleState;
import com.positivity.catalog.internal.entity.ProductStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Catalog product")
public class ProductDto {

    @Schema(
            description = "Product identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID id;

    @Schema(description = "Product name", example = "Heavy Duty Wrench", requiredMode = REQUIRED)
    private String name;

    @Schema(description = "Short product description", example = "Adjustable steel wrench", requiredMode = NOT_REQUIRED)
    private String shortDescription;

    @Schema(
            description = "Long product description",
            example = "Forged steel adjustable wrench with cushioned grip",
            requiredMode = NOT_REQUIRED)
    private String longDescription;

    @Schema(description = "Product description", example = "Heavy duty wrench", requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(
            description = "Product image URLs",
            example = "[\"https://cdn.example.com/img/wrench.jpg\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> images;

    @Schema(description = "Manufacturer part number", example = "MPN-9981", requiredMode = NOT_REQUIRED)
    private String manufacturerPartNumber;

    @Schema(
            description = "Manufacturer identifier",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = NOT_REQUIRED)
    private UUID manufacturerId;

    @Schema(description = "Manufacturer name", example = "AcmePro", requiredMode = NOT_REQUIRED)
    private String manufacturerName;

    @Schema(description = "Manufacturer warranty", example = "2 years limited", requiredMode = NOT_REQUIRED)
    private String manufacturerWarranty;

    @Schema(description = "Manufacturer brand", example = "AcmePro", requiredMode = NOT_REQUIRED)
    private String manufacturerBrand;

    @Schema(description = "Country of origin", example = "US", requiredMode = NOT_REQUIRED)
    private String countryOfOrigin;

    @Schema(description = "SKU", example = "SKU-12345", requiredMode = NOT_REQUIRED)
    private String sku;

    @Schema(description = "Manufacturer part number", example = "MPN-9981", requiredMode = NOT_REQUIRED)
    private String mpn;

    @Schema(description = "UPC", example = "012345678905", requiredMode = NOT_REQUIRED)
    private String upc;

    @Schema(description = "Attributes JSON", example = "{\"color\":\"black\"}", requiredMode = NOT_REQUIRED)
    private String attributes;

    @Schema(description = "Unit of measure", example = "EACH", requiredMode = NOT_REQUIRED)
    private String unitOfMeasure;

    @Schema(
            description = "Operational status",
            example = "ACTIVE",
            implementation = ProductStatus.class,
            requiredMode = NOT_REQUIRED)
    private ProductStatus status;

    @Schema(description = "Product code", example = "012345678905", requiredMode = NOT_REQUIRED)
    private String productCode;

    @Schema(
            description = "Product code type",
            example = "UPC",
            implementation = ProductCodeType.class,
            requiredMode = NOT_REQUIRED)
    private ProductCodeType productCodeType;

    @Schema(description = "Category detail", requiredMode = NOT_REQUIRED)
    private CategoryDto category;

    @Schema(description = "Subcategory detail", requiredMode = NOT_REQUIRED)
    private SubcategoryDto subcategory;

    @Schema(description = "Product type", example = "PHYSICAL", requiredMode = NOT_REQUIRED)
    private String type;

    @Schema(description = "Product dimensions", example = "[]", requiredMode = NOT_REQUIRED)
    private List<DimensionDto> dimensions;

    @Schema(description = "Material", example = "Steel", requiredMode = NOT_REQUIRED)
    private String material;

    @Schema(description = "Color", example = "Black", requiredMode = NOT_REQUIRED)
    private String color;

    @Schema(description = "Warranty", example = "1 year", requiredMode = NOT_REQUIRED)
    private String warranty;

    @Schema(description = "Detailed specifications JSON", example = "{\"weightKg\":1.2}", requiredMode = NOT_REQUIRED)
    private String specifications;

    @Schema(
            description = "Lifecycle state",
            example = "ACTIVE",
            implementation = ProductLifecycleState.class,
            requiredMode = NOT_REQUIRED)
    private ProductLifecycleState lifecycleState;

    @Schema(
            description = "Lifecycle state effective instant",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant lifecycleStateEffectiveAt;

    @Schema(
            description = "User that last changed lifecycle state",
            example = "8b8df63e-18d8-4bde-a8f4-88bc36bc57d7",
            requiredMode = NOT_REQUIRED)
    private UUID lastStateChangedBy;

    @Schema(
            description = "Last lifecycle state change instant",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant lastStateChangedAt;

    @Schema(description = "Lifecycle override reason", example = "Vendor discontinued", requiredMode = NOT_REQUIRED)
    private String lifecycleOverrideReason;

    @Schema(description = "Created timestamp", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Updated timestamp", example = "2026-01-16T11:00:00Z", requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
