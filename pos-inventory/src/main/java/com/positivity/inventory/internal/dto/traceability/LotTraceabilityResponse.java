package com.positivity.inventory.internal.dto.traceability;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.InventoryLotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Traceability tree for one lot (odoo-parity E5, issue #1051): its upstream origin (PO / ASN /
 * goods receipt, vendor, receipt date) and its downstream movement chain (putaway → pick →
 * consumption → return → scrap → transfer) reconstructed from the lot-tagged ledger entries in
 * chronological order.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Upstream/downstream traceability for a lot (odoo-parity E5)")
public class LotTraceabilityResponse {

    @Schema(description = "Identifier of the lot", requiredMode = REQUIRED)
    private UUID lotId;

    @Schema(description = "Stock item (catalog product id) the lot belongs to", requiredMode = REQUIRED)
    private String stockItemId;

    @Schema(description = "Vendor/manufacturer lot number", requiredMode = REQUIRED)
    private String lotNumber;

    @Schema(description = "Lifecycle status of the lot", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private InventoryLotStatus lotStatus;

    @Schema(description = "Upstream origin of the lot", requiredMode = REQUIRED)
    private TraceabilityUpstream upstream;

    @Schema(description = "Downstream movement chain, oldest first", requiredMode = REQUIRED)
    @Builder.Default
    private List<TraceabilityMovement> downstream = List.of();
}
