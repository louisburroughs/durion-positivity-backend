package com.positivity.inventory.internal.entity;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

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
    @CreatedDate
    @Column(nullable = false, updatable = false)
    private Instant timestamp;

    // Issue #48: Add location-level inventory tracking for per-location
    // availability.
    @Column
    private String locationId;

    /**
     * Optional notes or context for this transaction.
     */
    @Column(length = 2000)
    private String notes;

    /**
     * Timestamp when the task was created.
     */
    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /**
     * Timestamp of the last update to this task.
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

}
