package com.positivity.inventory.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Inventory summary for a specific storage location.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Inventory summary for a storage location")
public class LocationInventoryInquiryResponse {

    @Schema(description = "Storage location identifier", requiredMode = Schema.RequiredMode.REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Human-readable name of the location, from the site registry or the storage-location"
                    + " replica; null when it cannot be resolved",
            example = "Main Warehouse",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String locationName;

    @Schema(
            description = "Current on-hand quantity across all stock items at the location",
            example = "12",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal onHandQuantity;

    @Schema(
            description =
                    "Quantity available to promise after pending allocations. Note: reservation events are not yet factored in. "
                            + "Null for as-of (historical) requests: historical allocation state is not reliably "
                            + "reconstructable from ATP-neutral ledger events, so as-of responses carry on-hand only.",
            example = "8",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private BigDecimal availableToPromiseQuantity;

    @Schema(
            description = "Outstanding allocations at the location — the quantity subtracted from on-hand to"
                    + " get availableToPromiseQuantity. Null for as-of (historical) requests, same as"
                    + " availableToPromiseQuantity",
            example = "4",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private BigDecimal reservedQuantity;
}
