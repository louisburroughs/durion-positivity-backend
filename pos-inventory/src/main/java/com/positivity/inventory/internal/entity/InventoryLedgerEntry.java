package com.positivity.inventory.internal.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import com.positivity.inventory.internal.enums.InventoryLedgerEventType;
import com.positivity.shared.id.UUIDv7Id;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Immutable ledger entry representing a single inventory transaction.
 * 
 * <p>
 * This is the source of truth for all inventory quantity changes.
 * Entries are never updated or deleted, only created.
 */
@Entity
@Table(name = "inventory_ledger_entry")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class InventoryLedgerEntry {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID ledgerEntryId;

    /**
     * SKU identifier for the stock item.
     */
    @Column(nullable = false)
    private String stockItemId;

    /**
     * Reference to the source adjustment that created this entry.
     * Null for non-adjustment transactions (e.g., sales, receipts).
     */
    private UUID adjustmentId;

    /**
     * Type of inventory event (e.g., ADJUST_CYCLE_COUNT, GOODS_RECEIPT,
     * GOODS_ISSUE).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InventoryLedgerEventType eventType;

    /**
     * The signed quantity change for this transaction.
     * Positive for inbound, negative for outbound.
     */
    @Column(nullable = false)
    private Integer changeInQuantity;

    /**
     * The quantity on-hand after this transaction was applied.
     * Calculated at transaction time for verification.
     */
    @Column(nullable = false)
    private Integer quantityAfter;

    /**
     * Cost per unit at the time of the transaction.
     */
    @Column(precision = 19, scale = 4)
    private BigDecimal unitCost;

    /**
     * User who initiated or approved this transaction.
     */
    @Column(nullable = false)
    private String transactionUserId;

    /**
     * The exact time this transaction was posted to the ledger.
     * This is the authoritative timestamp for ordering transactions.
     */
    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    // Issue #48: Add location-level inventory tracking for per-location
    // availability.
    /**
     * Posting bucket for the ledger row, used for location-level availability
     * reads.
     *
     * <p>
     * This is distinct from directional metadata (`fromLocationId`,
     * `toLocationId`). For example, a TRANSFER_IN row posts to the destination, so
     * `locationId` and `toLocationId` are intentionally the same value.
     */
    @Column
    private UUID locationId;

    /**
     * Source location for TRANSFER movements.
     * Issue: CAP-215 Story #37
     */
    @Column
    private UUID fromLocationId;

    /**
     * Destination location for TRANSFER movements.
     * Issue: CAP-215 Story #37
     */
    @Column
    private UUID toLocationId;

    /**
     * Mandatory reason code for ADJUST movements.
     * Issue: CAP-215 Story #37
     */
    @Column(length = 100)
    private String reasonCode;

    /**
     * Optional reference to originating transaction (e.g., purchase order, work
     * order).
     * Issue: CAP-215 Story #37
     */
    @Column(length = 255)
    private String sourceTransactionId;

    /**
     * Unit of measure code for the quantity (e.g. EACH, KG, L).
     * Issue: CAP-215 Story #37
     */
    @Column(length = 50)
    private String unitOfMeasure;

    /**
     * Optional notes or context for this transaction.
     */
    @Column(length = 2000)
    private String notes;

    /**
     * Timestamp when this configuration was created.
     */
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp when this configuration was last updated.
     */
    @LastModifiedDate
    @Column(nullable = false)
    private Instant updatedAt;

}
