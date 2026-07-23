package com.positivity.domainevents.inventory;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: an open backorder was resolved (odoo-parity G1, issue #1046).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.backorder.resolved"} — one fact per resolved backorder (this is an
 * occurrence, not a snapshot: it is never re-emitted). A backorder resolves at most once, so this
 * fact is emitted exactly once per backorder even under replayed availability signals. pos-workorder
 * consumes it read-side to clear the workorder-line shortage marker.
 *
 * @param backorderId backorder record identifier (aggregate id of the fact)
 * @param workorderLineId workorder line whose demand was short
 * @param sku stock-item identifier that was short (ledger stock-item string)
 * @param quantityShort quantity that was resolved (positive; the whole backorder)
 * @param resolutionSource what drove the resolution: {@code AVAILABILITY},
 *     {@code REPLENISHMENT_RECEIPT}, or {@code MANUAL}
 * @param occurredAt when the backorder was resolved
 */
public record BackorderResolvedV1(
        @NonNull UUID backorderId,
        @NonNull UUID workorderLineId,
        @NonNull String sku,
        int quantityShort,
        @NonNull String resolutionSource,
        @Nullable UUID locationId,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.backorder.resolved";
    public static final int SCHEMA_VERSION = 1;

    public BackorderResolvedV1 {
        if (backorderId == null) {
            throw new IllegalArgumentException("backorderId must not be null");
        }
        if (workorderLineId == null) {
            throw new IllegalArgumentException("workorderLineId must not be null");
        }
        if (sku == null || sku.isBlank()) {
            throw new IllegalArgumentException("sku must not be blank");
        }
        if (quantityShort <= 0) {
            throw new IllegalArgumentException("quantityShort must be positive");
        }
        if (resolutionSource == null || resolutionSource.isBlank()) {
            throw new IllegalArgumentException("resolutionSource must not be blank");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}
