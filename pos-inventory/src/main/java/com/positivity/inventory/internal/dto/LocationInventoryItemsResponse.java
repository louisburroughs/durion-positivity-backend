package com.positivity.inventory.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * On-hand inventory contents (per stock item) for a single storage location.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "On-hand inventory contents for a storage location")
public class LocationInventoryItemsResponse {

    @Schema(description = "Storage location identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID locationId;

    @Schema(description = "Stock items currently on hand at the location", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<Item> items;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Schema(name = "LocationInventoryItem", description = "A single on-hand stock item line")
    public static class Item {

        @Schema(
                description = "Stock item identifier (product SKU)",
                example = "MICH-XC2-0001",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private String stockItemId;

        @Schema(
                description = "On-hand quantity of this stock item at the location",
                example = "24",
                requiredMode = Schema.RequiredMode.REQUIRED)
        private long onHandQuantity;
    }
}
