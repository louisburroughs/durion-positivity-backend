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
 * <p>Schema v2 (CAP #1315): mirrors {@link BackorderCreatedV1}'s widening — a resolved backorder's
 * demand line can now be a sales-order line instead of a workorder line. Exactly one of
 * {@code workorderLineId}/{@code salesOrderLineId} is non-null.
 *
 * @param backorderId backorder record identifier (aggregate id of the fact)
 * @param workorderLineId workorder line whose demand was short, when demand was from a workorder
 * @param salesOrderLineId sales-order line whose demand was short, when demand was from a sales
 *     order (additive, schema v2)
 * @param sku stock-item identifier that was short (ledger stock-item string)
 * @param quantityShort quantity that was resolved (positive; the whole backorder)
 * @param resolutionSource what drove the resolution: {@code AVAILABILITY},
 *     {@code REPLENISHMENT_RECEIPT}, or {@code MANUAL}
 * @param occurredAt when the backorder was resolved
 */
public record BackorderResolvedV1(
        @NonNull UUID backorderId,
        @Nullable UUID workorderLineId,
        @Nullable UUID salesOrderLineId,
        @NonNull String sku,
        int quantityShort,
        @NonNull String resolutionSource,
        @Nullable UUID locationId,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.backorder.resolved";
    public static final int SCHEMA_VERSION = 2;

    public BackorderResolvedV1 {
        if (backorderId == null) {
            throw new IllegalArgumentException("backorderId must not be null");
        }
        if ((workorderLineId == null) == (salesOrderLineId == null)) {
            throw new IllegalArgumentException("exactly one of workorderLineId/salesOrderLineId must be set");
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
