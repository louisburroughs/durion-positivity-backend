package com.positivity.inventory.internal.dto.traceability;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.InventorySerialStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Traceability tree for one serial unit (odoo-parity E5, issue #1051): its upstream origin
 * (PO / ASN / goods receipt, vendor, receipt date — resolved through the serial's lot when
 * present) and its downstream chain (receipt → issue → workorder → return/scrap) reconstructed
 * from the serial's receipt and consumption ledger linkages, plus any warranty-hold linkage
 * joined by serial number.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Upstream/downstream traceability for a serial unit (odoo-parity E5)")
public class SerialTraceabilityResponse {

    @Schema(description = "Identifier of the serial unit", requiredMode = REQUIRED)
    private UUID serialUnitId;

    @Schema(description = "Stock item (catalog product id) the unit belongs to", requiredMode = REQUIRED)
    private String stockItemId;

    @Schema(description = "Manufacturer/vendor serial number", requiredMode = REQUIRED)
    private String serialNumber;

    @Schema(description = "Lifecycle status of the unit", example = "ISSUED", requiredMode = NOT_REQUIRED)
    private InventorySerialStatus serialStatus;

    @Schema(description = "Lot the unit belongs to, when lot-linked", requiredMode = NOT_REQUIRED)
    private UUID lotId;

    @Schema(description = "Workorder the unit was consumed to, when known", requiredMode = NOT_REQUIRED)
    private String workorderId;

    @Schema(description = "Upstream origin of the serial", requiredMode = REQUIRED)
    private TraceabilityUpstream upstream;

    @Schema(description = "Downstream movement chain, oldest first", requiredMode = REQUIRED)
    @Builder.Default
    private List<TraceabilityMovement> downstream = List.of();

    @Schema(description = "Warranty part-return holds joined by serial number", requiredMode = REQUIRED)
    @Builder.Default
    private List<WarrantyHoldRef> warrantyHolds = List.of();
}
