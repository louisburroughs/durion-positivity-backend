package com.positivity.inventory.internal.dto;

import com.positivity.inventory.internal.entity.InventoryLedgerEventType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Response DTO representing a recorded inventory ledger entry.
 */
@Value
@Builder
public class InventoryLedgerEntryResponse {
    UUID ledgerEntryId;
    String stockItemId;
    UUID adjustmentId;
    InventoryLedgerEventType eventType;
    Integer changeInQuantity;
    Integer quantityAfter;
    BigDecimal unitCost;
    String transactionUserId;
    Instant timestamp;
    UUID locationId;
    UUID fromLocationId;
    UUID toLocationId;
    String reasonCode;
    String sourceTransactionId;
    String unitOfMeasure;
    String notes;
}
