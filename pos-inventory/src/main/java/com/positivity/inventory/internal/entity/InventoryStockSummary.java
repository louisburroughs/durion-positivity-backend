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
import java.math.BigDecimal;
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
 * (e.g. workorder consumption) aggregate on a NULL-location row.
 * {@code inTransitQty} arrived with odoo-parity C2 (issue #1036).
 *
 * <p>Per-lot rows (odoo-parity E1, issue #1038): {@code lotId} joined the unique key with
 * dual-row bookkeeping — the lot-agnostic row ({@code lotId} null) keeps aggregating EVERY
 * ledger entry for its (stockItemId, locationId) exactly as before E1 and remains what all
 * availability/rollup/forecast readers consume; a lot-tagged posting additionally applies the
 * same deltas to a (stockItemId, locationId, lotId) row, which only the lot read API consumes.
 */
@Entity
@Table(
        name = "inventory_stock_summary",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_inventory_stock_summary_key",
                        columnNames = {"stock_item_id", "location_id", "lot_id"}),
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

    /**
     * Lot dimension of the key (odoo-parity E1, issue #1038). Null on the lot-agnostic row —
     * the pre-E1 row every existing reader consumes; set on the additional per-lot rows that
     * lot-tagged postings maintain for the lot read API.
     */
    @Column(name = "lot_id")
    private UUID lotId;

    /** Net physical stock: sum of on-hand-affecting ledger deltas. */
    @Column(name = "on_hand", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal onHand = BigDecimal.ZERO;

    /** Outstanding hard allocations: ALLOCATION_CREATED - ALLOCATION_RELEASED. */
    @Column(name = "allocated", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal allocated = BigDecimal.ZERO;

    /** Outstanding soft reservations: RESERVATION_CREATED - RESERVATION_RELEASED. */
    @Column(name = "reserved", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal reserved = BigDecimal.ZERO;

    /** Available to promise: onHand - allocated (ADR-0001). */
    @Column(name = "atp", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal atp = BigDecimal.ZERO;

    /**
     * Stock in transit TOWARD this key (odoo-parity C2, issue #1036): dispatched transfer
     * quantity not yet received here. Keyed on the transfer's DESTINATION posting location —
     * a {@code TRANSFER_OUT} carrying a {@code toLocationId} adds its quantity to the
     * destination key; the matching {@code TRANSFER_IN} at the destination removes it (and
     * adds to {@code onHand}). Conserved invariant: source on-hand + destination in-transit +
     * destination on-hand is constant across dispatch → partial receive → final receive.
     */
    @Column(name = "in_transit_qty", nullable = false, precision = 19, scale = 4)
    @Builder.Default
    private BigDecimal inTransitQty = BigDecimal.ZERO;

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
