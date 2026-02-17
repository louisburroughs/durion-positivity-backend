package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.Data;

@Data
@Schema(description = "Catalog container")
public class CatalogDto {

    @Schema(description = "Catalog identifier", example = "0196cf6f-c8dd-7ee0-93e7-f48a5698a535")
    private UUID id;

    @Schema(description = "Catalog name", example = "Winter Catalog")
    private String name;

    @Schema(description = "Catalog description")
    private String description;

    @Schema(description = "Product IDs included in the catalog")
    private List<UUID> productIds;

    @Schema(description = "Service IDs included in the catalog")
    private List<UUID> serviceIds;

    @Schema(description = "Non-inventory product IDs included in the catalog")
    private List<UUID> nonInventoryProductIds;
}
