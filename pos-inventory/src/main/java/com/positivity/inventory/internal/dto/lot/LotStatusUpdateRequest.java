package com.positivity.inventory.internal.dto.lot;

import com.positivity.inventory.internal.enums.InventoryLotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request to change a lot's lifecycle status (odoo-parity E3, issue #1047): quarantine, recall, or
 * release back to ACTIVE. {@code CONSUMED} is not settable through the API — it is owned by the
 * ledger posting funnel's status reconciler.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Lot status change: QUARANTINE/RECALL a lot or release it back to ACTIVE")
public class LotStatusUpdateRequest {

    @Schema(
            description = "Target lifecycle status. One of ACTIVE (release), QUARANTINED, RECALLED."
                    + " CONSUMED is rejected (reconciler-owned).",
            example = "QUARANTINED",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "status is required")
    private InventoryLotStatus status;

    @Schema(
            description = "Reason for the status change (mandatory; recorded for traceability)",
            example = "Supplier recall notice R-2026-014",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "reason is required")
    private String reason;
}
