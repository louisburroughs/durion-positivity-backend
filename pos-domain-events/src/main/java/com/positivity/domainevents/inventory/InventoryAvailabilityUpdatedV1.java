package com.positivity.domainevents.inventory;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: the ledger-derived availability of one stock item at one location changed
 * (ADR-0044 §6, issue #899 Phase 5.3).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.availability.updated"} — one snapshot per (stockItemId,
 * locationId) touched in a business transaction, computed from the final persisted ledger
 * state. Consumers (pos-catalog) maintain an {@code ext_inventory_availability} replica for
 * product-detail display.
 *
 * <p><b>Freshness bound:</b> replica values are transactionally consistent with the ledger at
 * emission time and arrive within normal Kafka lag (seconds). They are display/planning inputs —
 * authoritative allocation decisions stay inside pos-inventory, which reads its own ledger.
 *
 * <p><b>Schema v2 (odoo-parity A2, issue #1028):</b> adds the additive forecast fields
 * {@code incomingQuantity}, {@code outgoingQuantity}, and {@code projectedAvailableQuantity}
 * (unbounded variant — no horizon cutoff). Events emitted under schema v1 deserialize with these
 * fields defaulting to {@code 0}; consumers must not treat a zero forecast on a v1 event as a
 * computed value.
 *
 * @param stockItemId owner stock-item identifier (the product SKU string)
 * @param locationId location the availability is scoped to (site or storage location, as
 *     recorded on the ledger entries)
 * @param onHandQuantity ledger on-hand sum
 * @param allocatedQuantity active hard-allocation sum
 * @param availableToPromiseQuantity onHand minus allocated
 * @param unitOfMeasure first non-blank unit of measure on the ledger (default {@code EACH})
 * @param incomingQuantity open expected supply: approved-PO open line quantity plus un-received
 *     ASN remainder (site-scoped when {@code locationId} is a site; global otherwise)
 * @param outgoingQuantity open expected demand: unallocated reservation remainders plus
 *     released-not-picked pick-task remainders
 * @param projectedAvailableQuantity {@code onHandQuantity + incomingQuantity - outgoingQuantity}
 */
public record InventoryAvailabilityUpdatedV1(
        @NonNull String stockItemId,
        @Nullable UUID locationId,
        int onHandQuantity,
        int allocatedQuantity,
        int availableToPromiseQuantity,
        @Nullable String unitOfMeasure,
        long incomingQuantity,
        long outgoingQuantity,
        long projectedAvailableQuantity) {

    public static final String EVENT_TYPE = "inventory.availability.updated";
    public static final int SCHEMA_VERSION = 2;

    public InventoryAvailabilityUpdatedV1 {
        if (stockItemId == null || stockItemId.isBlank()) {
            throw new IllegalArgumentException("stockItemId must not be blank");
        }
    }
}
