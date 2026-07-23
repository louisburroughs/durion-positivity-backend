package com.positivity.inventory.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
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
            description = "Current on-hand quantity across all stock items at the location",
            example = "12",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private long onHandQuantity;

    @Schema(
            description =
                    "Quantity available to promise after pending allocations. Note: reservation events are not yet factored in. "
                            + "Null for as-of (historical) requests: historical allocation state is not reliably "
                            + "reconstructable from ATP-neutral ledger events, so as-of responses carry on-hand only.",
            example = "8",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private Long availableToPromiseQuantity;
}
