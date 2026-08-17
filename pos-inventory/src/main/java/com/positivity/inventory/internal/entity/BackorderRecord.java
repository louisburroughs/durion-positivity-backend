package com.positivity.inventory.internal.entity;

import com.positivity.inventory.internal.enums.BackorderResolutionSource;
import com.positivity.inventory.internal.enums.BackorderStatus;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
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
 * Backorder record: unfulfilled demand for a workorder line held open until availability covers it
 * (odoo-parity G1, issue #1046).
 *
 * <p>Written when reservation promotion or pick confirmation hits insufficiency and the resolver
 * chooses BACKORDER. Opening a backorder posts the (ATP-neutral) {@code BACKORDER_CREATED} ledger
 * entry with {@code sourceTransactionId = backorderId}; resolving posts {@code BACKORDER_RESOLVED}.
 * The ledger stays authoritative for quantity — this record is a lifecycle document keyed by
 * {@code workorderLineId + sku}.
 *
 * <p>Timestamps follow ADR-0024 (Spring Data JPA auditing via {@link AuditingEntityListener} and an
 * injected {@code Clock}): {@code createdAt}/{@code updatedAt} are managed automatically;
 * {@code resolvedAt} is stamped by the resolution flow.
 *
 * <p>Duplicate-open guard: a partial unique index on {@code (workorderLineId, sku)} WHERE
 * {@code status = 'OPEN'} (V24) forbids two concurrent open backorders for the same line/SKU.
 */
@Entity
@Table(
        name = "backorder_record",
        indexes = {
            @Index(name = "idx_backorder_record_status", columnList = "status"),
            @Index(name = "idx_backorder_record_sku_location_status", columnList = "sku, location_id, status"),
            @Index(name = "idx_backorder_record_workorder_line", columnList = "workorder_line_id"),
            @Index(name = "idx_backorder_record_sales_order_line", columnList = "sales_order_line_id")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EntityListeners(AuditingEntityListener.class)
public class BackorderRecord {

    @Id
    @GeneratedValue
    @UUIDv7Id
    private UUID backorderId;

    /**
     * Workorder line whose demand was short, when demand came from a workorder. Exactly one of
     * this and {@link #salesOrderLineId} is set (CAP #1315 widened this table to accept either
     * demand source; DB CHECK enforces the invariant).
     */
    @Column(name = "workorder_line_id")
    private UUID workorderLineId;

    /**
     * Sales-order line whose demand was short, when demand came from a sales order (CAP #1315).
     * Exactly one of this and {@link #workorderLineId} is set.
     */
    @Column(name = "sales_order_line_id")
    private UUID salesOrderLineId;

    /** SKU / stock-item identifier that was short (ledger stock-item string). */
    @Column(name = "sku", nullable = false)
    private String sku;

    /** Site the demand is short at; matched by the availability-driven auto-resolution trigger. */
    @Column(name = "location_id")
    private UUID locationId;

    /** Quantity that could not be fulfilled; always positive. */
    @Column(name = "quantity_short", nullable = false)
    private Integer quantityShort;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BackorderStatus status;

    /** Set on resolve; null while OPEN or CANCELLED. */
    @Enumerated(EnumType.STRING)
    @Column(name = "resolution_source", length = 30)
    private BackorderResolutionSource resolutionSource;

    @Column(name = "created_by")
    private String createdBy;

    /** BACKORDER_CREATED ledger entry written when the backorder was opened. */
    @Column(name = "created_ledger_entry_id")
    private UUID createdLedgerEntryId;

    /** BACKORDER_RESOLVED ledger entry written when the backorder was resolved. */
    @Column(name = "resolved_ledger_entry_id")
    private UUID resolvedLedgerEntryId;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** When the backorder reached RESOLVED; null otherwise. */
    @Column(name = "resolved_at")
    private Instant resolvedAt;

    /** Whichever demand line this backorder is actually keyed on (CAP #1315). */
    @jakarta.persistence.Transient
    public UUID getDemandLineId() {
        return workorderLineId != null ? workorderLineId : salesOrderLineId;
    }
}
