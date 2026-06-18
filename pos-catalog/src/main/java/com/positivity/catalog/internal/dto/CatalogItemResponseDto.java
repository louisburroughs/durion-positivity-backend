package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Catalog item response")
public class CatalogItemResponseDto {

    @NotNull
    @Schema(
            description = "Item identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID id;

    @NotNull
    @Schema(description = "Item type", example = "product", requiredMode = REQUIRED)
    private String itemType;

    @Schema(description = "Item name", example = "Heavy Duty Wrench", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(description = "Short description", example = "Adjustable steel wrench", requiredMode = NOT_REQUIRED)
    private String shortDescription;

    @Schema(
            description = "Long description",
            example = "Forged steel adjustable wrench with cushioned grip",
            requiredMode = NOT_REQUIRED)
    private String longDescription;
}
