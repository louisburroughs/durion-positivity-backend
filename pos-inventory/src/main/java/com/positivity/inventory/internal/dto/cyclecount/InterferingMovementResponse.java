package com.positivity.inventory.internal.dto.cyclecount;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * One ledger movement that interfered with a cycle-count window: an
 * on-hand-affecting entry for the task's SKU/location recorded between task
 * creation and now (odoo-parity I2, issue #1026).
 */
@Schema(description = "Ledger movement recorded between cycle-count task creation and now for the task's SKU/location")
@Value
@Builder
public class InterferingMovementResponse {

    @Schema(
            description = "Unique identifier of the ledger entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    UUID ledgerEntryId;

    @Schema(description = "Type of inventory ledger event recorded", example = "GOODS_ISSUE", requiredMode = REQUIRED)
    @NotNull
    InventoryLedgerEventType eventType;

    @Schema(
            description = "Signed change in quantity applied by this entry (positive inbound, negative outbound)",
            example = "-3",
            requiredMode = REQUIRED)
    @NotNull
    Integer changeInQuantity;

    @Schema(description = "Running quantity after this entry was applied", example = "7", requiredMode = NOT_REQUIRED)
    Integer quantityAfter;

    @Schema(
            description = "Location the entry applies to",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    UUID locationId;

    @Schema(
            description = "Business timestamp of the inventory event",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    Instant timestamp;

    @Schema(
            description = "Identifier of the user who initiated the transaction",
            example = "user-10042",
            requiredMode = NOT_REQUIRED)
    String transactionUserId;

    @Schema(description = "Free-text notes recorded with the entry", requiredMode = NOT_REQUIRED)
    String notes;
}
