package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Stored on-hand snapshot (issue #1024, odoo-parity A1): one row per
 * (stockItemId, locationId) derived from the append-only inventory ledger.
 *
 * <p>Maintained transactionally with each ledger append by
 * {@code LedgerPostingService}; never written by any other path. The ledger
 * remains the source of truth — {@code StockSummaryRebuildService} rebuilds
 * this table from scratch and {@code StockSummaryDriftVerifier} reports drift.
 *
 * <p>{@code locationId} is nullable: ledger entries posted without a location
 * (e.g. workorder consumption) aggregate on a NULL-location row. Later stories
 * add a nullable {@code lotId} to the uniqueness and an {@code inTransitQty}
 * column without reshaping the table.
 */
@Entity
@Table(
        name = "inventory_stock_summary",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_inventory_stock_summary_key",
                        columnNames = {"stock_item_id", "location_id"}),
        indexes = @Index(name = "idx_inventory_stock_summary_location", columnList = "location_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class InventoryStockSummary {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID summaryId;

    @Column(name = "stock_item_id", nullable = false)
    private String stockItemId;

    @Column(name = "location_id")
    private UUID locationId;

    /** Net physical stock: sum of on-hand-affecting ledger deltas. */
    @Column(name = "on_hand", nullable = false)
    private long onHand;

    /** Outstanding hard allocations: ALLOCATION_CREATED - ALLOCATION_RELEASED. */
    @Column(name = "allocated", nullable = false)
    private long allocated;

    /** Outstanding soft reservations: RESERVATION_CREATED - RESERVATION_RELEASED. */
    @Column(name = "reserved", nullable = false)
    private long reserved;

    /** Available to promise: onHand - allocated (ADR-0001). */
    @Column(name = "atp", nullable = false)
    private long atp;

    /** Ledger entry that last touched this row. */
    @Column(name = "last_ledger_entry_id")
    private UUID lastLedgerEntryId;

    /** Timestamp of the ledger entry that last touched this row. */
    @Column(name = "last_event_at")
    private Instant lastEventAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
