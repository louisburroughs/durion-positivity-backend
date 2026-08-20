package com.positivity.domainevents.inventory;

import java.math.BigDecimal;
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
 * <p><b>Quantities are decimal (ADR-0055, issue #1414).</b> A product's stock divisibility is
 * declared per product by the catalog ({@code product_uom.precision_scale}); a product declaring
 * scale 0 — which is every product until one is seeded otherwise — still only ever produces
 * integral values here. The type carries what a divisible product needs; the declaration is what
 * decides whether any given product may use it. Compare these with {@code compareTo}, never
 * {@code equals}: {@code 1} and {@code 1.0} are the same quantity and are not equal.
 *
 * <p><b>Schema v2 (odoo-parity A2, issue #1028):</b> adds the additive forecast fields
 * {@code incomingQuantity}, {@code outgoingQuantity}, and {@code projectedAvailableQuantity}
 * (unbounded variant — no horizon cutoff). Events emitted under schema v1 deserialize with these
 * fields defaulting to {@code 0}; consumers must not treat a zero forecast on a v1 event as a
 * computed value. They are marked {@code @Nullable} because a v1 payload legitimately omits them;
 * the canonical constructor substitutes {@code ZERO}, so the accessors never return null.
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
        @NonNull BigDecimal onHandQuantity,
        @NonNull BigDecimal allocatedQuantity,
        @NonNull BigDecimal availableToPromiseQuantity,
        @Nullable String unitOfMeasure,
        @Nullable BigDecimal incomingQuantity,
        @Nullable BigDecimal outgoingQuantity,
        @Nullable BigDecimal projectedAvailableQuantity) {

    public static final String EVENT_TYPE = "inventory.availability.updated";
    public static final int SCHEMA_VERSION = 2;

    public InventoryAvailabilityUpdatedV1 {
        if (stockItemId == null || stockItemId.isBlank()) {
            throw new IllegalArgumentException("stockItemId must not be blank");
        }
        onHandQuantity = required(onHandQuantity, "onHandQuantity");
        allocatedQuantity = required(allocatedQuantity, "allocatedQuantity");
        availableToPromiseQuantity = required(availableToPromiseQuantity, "availableToPromiseQuantity");
        // The forecast triple arrived in schema v2 and is absent from a v1 payload, so it
        // defaults rather than throwing — the same "absent means zero" the primitive `long`
        // fields gave it before the widening.
        incomingQuantity = incomingQuantity == null ? BigDecimal.ZERO : incomingQuantity;
        outgoingQuantity = outgoingQuantity == null ? BigDecimal.ZERO : outgoingQuantity;
        projectedAvailableQuantity = projectedAvailableQuantity == null ? BigDecimal.ZERO : projectedAvailableQuantity;
    }

    /**
     * The three core quantities are required. They were primitives before the widening, and an
     * omitted primitive is a databind failure the consumers already count and reject — keeping
     * them mandatory keeps a truncated payload loud rather than letting it land as a silent zero
     * that would read as "nothing on hand".
     */
    private static BigDecimal required(@Nullable BigDecimal value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        return value;
    }
}
