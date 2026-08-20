package com.positivity.workorder.internal.entity;

import com.positivity.shared.id.UUIDv7Id;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

/**
 * Read-only owned-availability replica fed by {@code inventory.availability.updated} facts on
 * {@code inventory.events.v1} (ADR-0044 §6, CAP #1315).
 *
 * <p>pos-inventory owns these facts; nothing in this module may write the table except
 * {@code InventoryEventsListener}. Backs the per-part gate at issue time — "can this part really
 * be handed to a technician right now" — which before this had no answer at all.
 *
 * <p><b>Freshness bound:</b> transactionally consistent with the owner's ledger at emission time,
 * arriving within normal Kafka lag. The bound matters more here than on the sales side: a short
 * answer blocks a part issue rather than admitting a backorder, so a stale replica can briefly
 * withhold a part that is actually on the shelf. It cannot do the reverse for long — pos-inventory
 * re-decides authoritatively against its own ledger when it handles the reservation-request
 * command, and the outcome fact carries that answer back.
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

    @Column(name = "on_hand_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal onHandQuantity;

    @Column(name = "allocated_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedQuantity;

    @Column(name = "available_to_promise_quantity", nullable = false, precision = 19, scale = 4)
    private BigDecimal availableToPromiseQuantity;

    @Column(name = "unit_of_measure", length = 32)
    private String unitOfMeasure;

    @Column(name = "aggregate_version", nullable = false)
    private long aggregateVersion;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Explicit dependency hook for the ArchUnit UUIDv7 rule: the primary key is the owner's
     * deterministic aggregate id for the (stockItemId, locationId) pair.
     */
    @Transient
    public Class<?> uuidv7Dependency() {
        return UUIDv7Id.class;
    }
}
