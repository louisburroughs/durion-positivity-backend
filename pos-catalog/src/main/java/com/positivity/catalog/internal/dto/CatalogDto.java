package com.positivity.catalog.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Catalog container")
public class CatalogDto {

    @NotNull
    @Schema(
            description = "Catalog identifier",
            example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535",
            requiredMode = REQUIRED)
    private UUID id;

    @NotNull
    @Schema(description = "Catalog name", example = "Winter Catalog", requiredMode = REQUIRED)
    private String name;

    @Schema(description = "Catalog description", example = "Seasonal winter product catalog", requiredMode = NOT_REQUIRED)
    private String description;

    @Schema(
            description = "Product IDs included in the catalog",
            example = "[\"0196cf6f-c8dd-7ee0-93e7-f48a5698a535\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> productIds;

    @Schema(
            description = "Service IDs included in the catalog",
            example = "[\"0196cf6f-c8dd-7ee0-93e7-f48a5698a536\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> serviceIds;

    @Schema(
            description = "Non-inventory product IDs included in the catalog",
            example = "[\"0196cf6f-c8dd-7ee0-93e7-f48a5698a537\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> nonInventoryProductIds;
}
