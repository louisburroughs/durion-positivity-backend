package com.positivity.inventory.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Schema(description = "Inventory ledger entry record capturing a single stock movement or allocation event")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryLedgerEntryDto {

    @Schema(
            description = "Unique identifier of the ledger entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID ledgerEntryId;

    @Schema(
            description = "Identifier of the stock item the entry applies to",
            example = "SKU-10042",
            requiredMode = REQUIRED)
    @NotNull
    private String stockItemId;

    @Schema(
            description = "Identifier of the adjustment that produced this entry, if any",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID adjustmentId;

    @Schema(
            description = "Type of inventory ledger event recorded",
            example = "GOODS_RECEIPT",
            requiredMode = REQUIRED)
    @NotNull
    private InventoryLedgerEventType eventType;

    @Schema(
            description = "Signed change in quantity applied by this entry (positive inbound, negative outbound)",
            example = "12",
            requiredMode = REQUIRED)
    @NotNull
    private Integer changeInQuantity;

    @Schema(
            description = "Running quantity after this entry was applied",
            example = "120",
            requiredMode = REQUIRED)
    @NotNull
    private Integer quantityAfter;

    @Schema(
            description = "Unit cost associated with this movement, if recorded",
            example = "4.50",
            requiredMode = NOT_REQUIRED)
    private BigDecimal unitCost;

    @Schema(
            description = "Identifier of the user who initiated the transaction",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = NOT_REQUIRED)
    private String transactionUserId;

    @Schema(
            description = "Business timestamp of the inventory event",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant timestamp;

    @Schema(
            description = "Location the entry applies to",
            example = "01960003-0000-7000-8000-000000000004",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(
            description = "Source location for transfer events",
            example = "01960003-0000-7000-8000-000000000005",
            requiredMode = NOT_REQUIRED)
    private UUID fromLocationId;

    @Schema(
            description = "Destination location for transfer events",
            example = "01960003-0000-7000-8000-000000000006",
            requiredMode = NOT_REQUIRED)
    private UUID toLocationId;

    @Schema(
            description = "Reason code explaining the entry",
            example = "CYCLE_COUNT",
            requiredMode = NOT_REQUIRED)
    private String reasonCode;

    @Schema(
            description = "Identifier of the originating source transaction",
            example = "01960003-0000-7000-8000-000000000007",
            requiredMode = NOT_REQUIRED)
    private String sourceTransactionId;

    @Schema(
            description = "Unit of measure code for the quantities (e.g. EACH, KG)",
            example = "EACH",
            requiredMode = NOT_REQUIRED)
    private String unitOfMeasure;

    @Schema(
            description = "Free-text notes attached to the entry",
            example = "Variance corrected after recount",
            requiredMode = NOT_REQUIRED)
    private String notes;

    @Schema(
            description = "Timestamp when the entry record was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the entry record was last updated",
            example = "2026-01-15T09:35:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
