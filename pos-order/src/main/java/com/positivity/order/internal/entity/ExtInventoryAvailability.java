package com.positivity.order.internal.entity;

import com.positivity.shared.id.AssignedIdentifier;
import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * Read-only inventory availability replica fed by {@code inventory.events.v1} (ADR-0044 §6,
 * CAP #1315). pos-inventory owns these facts; nothing in this module may write the table except
 * {@code InventoryEventsListener}.
 *
 * <p>Backs the checkout availability gate that replaced the advisory always-sufficient stub.
 * <b>Freshness bound:</b> values are transactionally consistent with the owner's ledger at
 * emission time and arrive within normal Kafka lag (seconds). That bound is acceptable here
 * precisely because a short line is admitted as a backorder rather than rejected — a stale
 * replica can only mislabel a line for a few seconds, never block a sale. The authoritative
 * decision is made by pos-inventory against its own ledger when it handles the resulting
 * reservation-request command.
 *
 * <p>The primary key is the producer's deterministic aggregate id for the
 * (stockItemId, locationId) pair; lookups go through the (sku, location) columns.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@EntityListeners(AuditingEntityListener.class)
@Table(name = "ext_inventory_availability")
public class ExtInventoryAvailability {

    @Id
    @AssignedIdentifier("pos-inventory's own aggregate id for the (stockItemId, locationId) pair; a locally"
            + " minted id would break the only correspondence that lets a later fact update this row")
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "stock_item_id", nullable = false)
    private String stockItemId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "on_hand_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal onHandQuantity;

    @Column(name = "allocated_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedQuantity;

    @Column(name = "available_to_promise_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableToPromiseQuantity;

    @Column(name = "unit_of_measure")
    private String unitOfMeasure;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    /**
     * When this module last wrote the row, not when the owner emitted the fact — which is exactly
     * what the staleness signal needs to answer "is our copy behind?" (ADR-0024).
     */
    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule (ADR-0013): ids on this replica are
     * minted by the owning module; this replica stores them verbatim.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
