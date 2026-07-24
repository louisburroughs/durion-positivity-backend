package com.positivity.inventory.internal.dto.serial;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.InventorySerialStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "A serial unit master record for a SERIAL-tracked stock item (odoo-parity E4)")
public class SerialUnitResponse {

    @Schema(
            description = "Identifier of the serial unit",
            example = "01990003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID serialUnitId;

    @Schema(
            description = "Stock item (catalog product id) the unit belongs to",
            example = "01990003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    private String stockItemId;

    @Schema(description = "Manufacturer/vendor serial number, unique per stock item", requiredMode = REQUIRED)
    private String serialNumber;

    @Schema(description = "Lifecycle status of the unit", example = "IN_STOCK", requiredMode = REQUIRED)
    private InventorySerialStatus status;

    @Schema(
            description = "Current location while IN_STOCK; null once the unit has left stock",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(description = "Optional lot linkage", requiredMode = NOT_REQUIRED)
    private UUID lotId;

    @Schema(description = "Workorder the unit was consumed to, when known", requiredMode = NOT_REQUIRED)
    private String workorderId;

    @Schema(
            description = "The receipt ledger entry that enumerated (or last re-stocked) this unit",
            requiredMode = NOT_REQUIRED)
    private UUID receiptLedgerEntryId;

    @Schema(description = "The outbound ledger entry that last consumed this unit", requiredMode = NOT_REQUIRED)
    private UUID consumptionLedgerEntryId;

    @Schema(description = "When the unit was received into stock", requiredMode = NOT_REQUIRED)
    private Instant receivedAt;

    @Schema(description = "When the unit last left stock", requiredMode = NOT_REQUIRED)
    private Instant consumedAt;

    @Schema(description = "Creation timestamp", requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(description = "Last update timestamp", requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
