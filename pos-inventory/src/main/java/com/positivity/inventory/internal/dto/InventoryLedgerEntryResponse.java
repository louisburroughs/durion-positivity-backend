package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Response DTO representing a recorded inventory ledger entry.
 */
@Schema(description = "Response representing a recorded inventory ledger entry")
@Value
@Builder
public class InventoryLedgerEntryResponse {

    @Schema(
            description = "Unique identifier of the ledger entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    UUID ledgerEntryId;

    @Schema(
            description = "Identifier of the stock item the entry applies to",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    String stockItemId;

    @Schema(
            description = "Identifier of the adjustment that produced this entry, if any",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    UUID adjustmentId;

    @Schema(description = "Type of inventory ledger event recorded", example = "GOODS_RECEIPT", requiredMode = REQUIRED)
    @NotNull
    InventoryLedgerEventType eventType;

    @Schema(
            description = "Signed change in quantity applied by this entry (positive inbound, negative outbound)",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull
    Integer changeInQuantity;

    @Schema(description = "Running quantity after this entry was applied", example = "120", requiredMode = REQUIRED)
    @NotNull
    Integer quantityAfter;

    @Schema(
            description = "Unit cost associated with this movement, if recorded",
            example = "4.50",
            requiredMode = NOT_REQUIRED)
    BigDecimal unitCost;

    @Schema(
            description = "Identifier of the user who initiated the transaction",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    String transactionUserId;

    @Schema(
            description = "Business timestamp of the inventory event",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    Instant timestamp;

    @Schema(
            description = "Location the entry applies to",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    UUID locationId;

    @Schema(
            description = "Source location for transfer events",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    UUID fromLocationId;

    @Schema(
            description = "Destination location for transfer events",
            example = "01960003-0000-7000-8000-000000000006",
            requiredMode = NOT_REQUIRED)
    UUID toLocationId;

    @Schema(description = "Reason code explaining the entry", example = "CYCLE_COUNT", requiredMode = NOT_REQUIRED)
    String reasonCode;

    @Schema(
            description = "Identifier of the originating source transaction",
            example = "01960003-0000-7000-8000-000000000007",
            requiredMode = NOT_REQUIRED)
    String sourceTransactionId;

    @Schema(
            description = "Unit of measure code for the quantities (e.g. EACH, KG)",
            example = "EACH",
            requiredMode = NOT_REQUIRED)
    String unitOfMeasure;

    @Schema(
            description = "Free-text notes attached to the entry",
            example = "Variance corrected after recount",
            requiredMode = NOT_REQUIRED)
    String notes;
}
