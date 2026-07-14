package com.positivity.catalog.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Read-only inventory availability replica fed by {@code inventory.events.v1} (ADR-0044 §6,
 * #899). pos-inventory owns these facts; nothing in this module may write the table except the
 * event consumer.
 *
 * <p>Serves product-detail availability display, previously answered by the retired
 * {@code InventoryClientImpl}. <b>Freshness bound:</b> values are transactionally consistent
 * with the owner's ledger at emission time and arrive within normal Kafka lag (seconds) —
 * display/planning inputs, never allocation decisions.
 *
 * <p>The primary key is the producer's deterministic aggregate id for the
 * (stockItemId, locationId) pair; lookups go through the (sku, location) columns.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "ext_inventory_availability")
public class ExtInventoryAvailabilityReplica {

    @Id
    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "stock_item_id", nullable = false)
    private String stockItemId;

    @Column(name = "location_id")
    private UUID locationId;

    @Column(name = "on_hand_quantity", nullable = false)
    private int onHandQuantity;

    @Column(name = "allocated_quantity", nullable = false)
    private int allocatedQuantity;

    @Column(name = "available_to_promise_quantity", nullable = false)
    private int availableToPromiseQuantity;

    @Column(name = "unit_of_measure")
    private String unitOfMeasure;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

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
