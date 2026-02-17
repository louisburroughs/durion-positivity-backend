package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Catalog item response")
public class CatalogItemResponseDto {

    @Schema(description = "Item identifier")
    private UUID id;

    @Schema(description = "Item type", example = "product")
    private String itemType;

    @Schema(description = "Item name")
    private String name;

    @Schema(description = "Short description")
    private String shortDescription;

    @Schema(description = "Long description")
    private String longDescription;
}
