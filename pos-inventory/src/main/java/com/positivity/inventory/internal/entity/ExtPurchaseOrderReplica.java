package com.positivity.inventory.internal.entity;

import com.positivity.shared.id.UUIDv7Generator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Local projection of a purchase order owned by pos-order (ADR-0044 R3, ADR-0049 amendment
 * 2026-08-15).
 *
 * <p>Written only by the {@code order.events.v1} consumer. Nothing else in this module may write it:
 * a change to what was ordered belongs to the owner, and received quantities are pos-inventory's own
 * fact kept in its own tables. The open quantity here is what the owner last said, not a number this
 * module maintains.
 *
 * <p>This is not a convenience copy for a receiving screen. Availability-to-promise sums open
 * quantities from these rows, so a missing or stale row changes what a customer is promised — and it
 * errs optimistically, offering stock nobody ordered.
 */
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(
        name = "ext_purchase_order",
        indexes = {
            @Index(name = "idx_ext_po_status_site", columnList = "status, ship_to_location_id"),
            @Index(name = "idx_ext_po_expected_delivery", columnList = "expected_delivery_date")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtPurchaseOrderReplica {

    @Id
    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;

    @Column(name = "po_number", nullable = false)
    private String poNumber;

    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    /** DRAFT, APPROVED, PARTIALLY_RECEIVED, FULLY_RECEIVED, CLOSED or CANCELLED, as the owner said. */
    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "ship_to_location_id")
    private UUID shipToLocationId;

    /**
     * Null when the order carries no promised delivery date.
     *
     * <p>The horizon-bounded supply query excludes undated orders rather than treating them as
     * arriving today, so this must stay nullable and must never be defaulted on the way in.
     */
    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @Column(name = "currency", length = 8)
    private String currency;

    @Column(name = "grand_total_minor")
    private Long grandTotalMinor;

    /** Value still outstanding; what the over-receipt guard compares a receipt against. */
    @Column(name = "open_balance_minor")
    private Long openBalanceMinor;

    /** Owner's optimistic-lock version; the stale guard compares against this. */
    @Column(name = "aggregate_version", nullable = false)
    @Builder.Default
    private long aggregateVersion = 0L;

    /** When the owner said this state was reached, as distinct from when we applied it. */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): the primary key IS a
     * UUIDv7 minted by the owning module; this replica stores it verbatim. Minting one locally
     * would break the correspondence with the order the owner published.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Generator.class;
    }
}
