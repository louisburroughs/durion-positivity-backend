package com.positivity.inventory.internal.dto.traceability;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One node in the downstream movement chain of a lot/serial (odoo-parity E5, issue #1051):
 * a single ledger entry — putaway, pick, consumption, return, scrap, or transfer — with its
 * document linkage. Rendered chronologically to reconstruct the lifecycle.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "One ledger movement in a lot/serial downstream chain")
public class TraceabilityMovement {

    @Schema(description = "Identifier of the ledger entry", requiredMode = REQUIRED)
    private UUID ledgerEntryId;

    @Schema(description = "Ledger event type", example = "PUTAWAY", requiredMode = REQUIRED)
    private InventoryLedgerEventType eventType;

    @Schema(description = "When the movement was posted", requiredMode = REQUIRED)
    private Instant timestamp;

    @Schema(description = "Signed quantity change (positive inbound, negative outbound)", requiredMode = REQUIRED)
    private BigDecimal changeInQuantity;

    @Schema(description = "On-hand quantity after this movement at its location", requiredMode = NOT_REQUIRED)
    private BigDecimal quantityAfter;

    @Schema(description = "Location the movement affected", requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(description = "Source location for a paired move (putaway/transfer out)", requiredMode = NOT_REQUIRED)
    private UUID fromLocationId;

    @Schema(description = "Destination location for a paired move (putaway/transfer in)", requiredMode = NOT_REQUIRED)
    private UUID toLocationId;

    @Schema(description = "Lot stamped on the movement, when lot-tracked", requiredMode = NOT_REQUIRED)
    private UUID lotId;

    @Schema(description = "Source transaction/document reference the posting carried", requiredMode = NOT_REQUIRED)
    private String sourceTransactionId;

    @Schema(description = "Free-text note recorded on the ledger entry", requiredMode = NOT_REQUIRED)
    private String notes;
}
