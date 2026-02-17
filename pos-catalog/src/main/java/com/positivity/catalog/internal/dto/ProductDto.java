package com.positivity.catalog.internal.dto;

import com.positivity.catalog.internal.entity.ProductCodeType;
import com.positivity.catalog.internal.entity.ProductLifecycleState;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Catalog product")
public class ProductDto {

    @Schema(description = "Product identifier", example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535")
    private UUID id;

    @Schema(description = "Product name", example = "Heavy Duty Wrench")
    private String name;

    @Schema(description = "Short product description")
    private String shortDescription;

    @Schema(description = "Long product description")
    private String longDescription;

    @Schema(description = "Product image URLs")
    private List<String> images;

    @Schema(description = "Manufacturer part number")
    private String manufacturerPartNumber;

    @Schema(description = "Manufacturer identifier")
    private String manufacturerId;

    @Schema(description = "Manufacturer name")
    private String manufacturerName;

    @Schema(description = "Manufacturer warranty")
    private String manufacturerWarranty;

    @Schema(description = "Manufacturer brand")
    private String manufacturerBrand;

    @Schema(description = "Country of origin")
    private String countryOfOrigin;

    @Schema(description = "SKU")
    private String sku;

    @Schema(description = "Product code")
    private String productCode;

    @Schema(description = "Product code type", implementation = ProductCodeType.class)
    private ProductCodeType productCodeType;

    @Schema(description = "Category detail")
    private CategoryDto category;

    @Schema(description = "Subcategory detail")
    private SubcategoryDto subcategory;

    @Schema(description = "Product type")
    private String type;

    @Schema(description = "Product dimensions")
    private List<DimensionDto> dimensions;

    @Schema(description = "Material")
    private String material;

    @Schema(description = "Color")
    private String color;

    @Schema(description = "Warranty")
    private String warranty;

    @Schema(description = "Detailed specifications JSON")
    private String specifications;

    @Schema(description = "Lifecycle state", implementation = ProductLifecycleState.class)
    private ProductLifecycleState lifecycleState;

    @Schema(description = "Lifecycle state effective instant")
    private Instant lifecycleStateEffectiveAt;

    @Schema(description = "User that last changed lifecycle state")
    private UUID lastStateChangedBy;

    @Schema(description = "Last lifecycle state change instant")
    private Instant lastStateChangedAt;

    @Schema(description = "Lifecycle override reason")
    private String lifecycleOverrideReason;
}
