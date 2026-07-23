package com.positivity.domainevents.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: expected supply for a SKU was dropped because a purchase order was closed or cancelled
 * while it still had open (un-received) quantity (odoo-parity A4/G20, issue #1028).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.expected-supply.dropped"} — one fact per (purchase order, SKU)
 * whose open quantity is discarded. Shortage tooling and availability projections react by
 * removing the dropped quantity from {@code incomingQuantity} expectations.
 *
 * @param sku stock-item identifier the supply was expected for (PO line {@code skuId} rendered
 *     as the ledger's stock-item string)
 * @param siteId ship-to site of the purchase order, when recorded
 * @param droppedQuantity open quantity that will no longer arrive (base UoM, decimal — PO lines
 *     carry decimal quantities)
 * @param poId purchase order whose open supply was dropped
 * @param reason terminal transition that dropped the supply: {@code CLOSED} or {@code CANCELLED}
 * @param occurredAt when the purchase order reached the terminal status
 */
public record ExpectedSupplyDroppedV1(
        @NonNull String sku,
        @Nullable UUID siteId,
        @NonNull BigDecimal droppedQuantity,
        @NonNull UUID poId,
        @NonNull String reason,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.expected-supply.dropped";
    public static final int SCHEMA_VERSION = 1;

    /** Allowed {@code reason} values. */
    public static final String REASON_CLOSED = "CLOSED";

    public static final String REASON_CANCELLED = "CANCELLED";

    private static final Set<String> ALLOWED_REASONS = Set.of(REASON_CLOSED, REASON_CANCELLED);

    public ExpectedSupplyDroppedV1 {
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (droppedQuantity == null || droppedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("droppedQuantity must be positive");
        }
        if (poId == null) {
            throw new IllegalArgumentException("poId must not be null");
        }
        if (reason == null || !ALLOWED_REASONS.contains(reason)) {
            throw new IllegalArgumentException("reason must be one of " + ALLOWED_REASONS + " but was: " + reason);
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}
