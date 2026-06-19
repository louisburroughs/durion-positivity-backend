package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Update product request")
public class ProductUpdateRequestDto {

    @NotBlank
    @Schema(
            description = "Product display name",
            example = "Premium Brake Pad Set",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String name;

    @NotBlank
    @Schema(
            description = "Product description",
            example = "Ceramic brake pads for front axle",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String description;

    @NotBlank
    @Schema(description = "Unit of measure for the product", example = "EACH", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String unitOfMeasure;

    @Schema(
            description = "Identifier of the product manufacturer",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID manufacturerId;

    @Schema(
            description = "Identifier of the product category",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private UUID categoryId;

    @Schema(description = "Stock keeping unit", example = "BP-CER-FRT-001", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String sku;

    @NotBlank
    @Schema(
            description = "Manufacturer part number",
            example = "ACME-BP-1234",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String mpn;

    @Schema(
            description = "Universal product code",
            example = "012345678905",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String upc;

    @Schema(description = "JSON object represented as string")
    private String attributes;
}
