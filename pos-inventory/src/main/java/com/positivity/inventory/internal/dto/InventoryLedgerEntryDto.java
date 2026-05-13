package com.positivity.inventory.internal.dto;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryLedgerEntryDto {
    private UUID ledgerEntryId;
    private String stockItemId;
    private UUID adjustmentId;
    private InventoryLedgerEventType eventType;
    private Integer changeInQuantity;
    private Integer quantityAfter;
    private BigDecimal unitCost;
    private String transactionUserId;
    private Instant timestamp;
    private UUID locationId;
    private UUID fromLocationId;
    private UUID toLocationId;
    private String reasonCode;
    private String sourceTransactionId;
    private String unitOfMeasure;
    private String notes;
    private Instant createdAt;
    private Instant updatedAt;
}
