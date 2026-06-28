package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Schema(description = "Create product request")
public class ProductCreateRequestDto {

    @NotBlank
    @Schema(description = "Product name", example = "Heavy Duty Wrench", requiredMode = REQUIRED)
    private String name;

    @NotBlank
    @Schema(description = "Product description", example = "Forged steel adjustable wrench", requiredMode = REQUIRED)
    private String description;

    @NotBlank
    @Schema(description = "Unit of measure", example = "EACH", requiredMode = REQUIRED)
    private String unitOfMeasure;

    @Schema(
            description = "Manufacturer identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = NOT_REQUIRED)
    private UUID manufacturerId;

    @Schema(
            description = "Category identifier",
            example = "2c018664-dfea-46ef-a838-6b9dbf8f9b1d",
            requiredMode = NOT_REQUIRED)
    private UUID categoryId;

    @NotBlank
    @Schema(description = "Stock keeping unit", example = "SKU-12345", requiredMode = REQUIRED)
    private String sku;

    @NotBlank
    @Schema(description = "Manufacturer part number", example = "MPN-9981", requiredMode = REQUIRED)
    private String mpn;

    @Schema(description = "Universal product code", example = "012345678905", requiredMode = NOT_REQUIRED)
    private String upc;

    @Schema(
            description = "JSON object represented as string",
            example = "{\"color\":\"black\"}",
            requiredMode = NOT_REQUIRED)
    private String attributes;
}
