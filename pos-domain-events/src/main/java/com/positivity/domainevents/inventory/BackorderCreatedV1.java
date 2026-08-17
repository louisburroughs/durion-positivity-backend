package com.positivity.domainevents.inventory;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a backorder was opened for demand that could not be met (odoo-parity G1, issue #1046).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.backorder.created"} — one fact per opened backorder (this is an
 * occurrence, not a snapshot: it is never re-emitted with newer state). pos-workorder consumes it
 * read-side for workorder-line shortage visibility (no contract change required from workexec to
 * unblock G1).
 *
 * <p>Schema v2 (CAP #1315): a backorder's demand line can now be a sales-order line instead of a
 * workorder line ({@code BackorderRecord} was widened to accept either). Exactly one of
 * {@code workorderLineId}/{@code salesOrderLineId} is non-null on every event; v1 consumers that
 * only read {@code workorderLineId} continue to see it populated for every backorder they'd have
 * seen before, and simply never observe the sales-order-only ones (a null they'd have to check for
 * regardless, since v1 never guaranteed the field either way for a type it didn't know about).
 *
 * @param backorderId backorder record identifier (aggregate id of the fact)
 * @param workorderLineId workorder line whose demand was short, when demand was from a workorder
 * @param salesOrderLineId sales-order line whose demand was short, when demand was from a sales
 *     order (additive, schema v2)
 * @param sku stock-item identifier that was short (ledger stock-item string)
 * @param quantityShort quantity that could not be fulfilled (positive)
 * @param occurredAt when the backorder was opened
 */
public record BackorderCreatedV1(
        @NonNull UUID backorderId,
        @Nullable UUID workorderLineId,
        @Nullable UUID salesOrderLineId,
        @NonNull String sku,
        int quantityShort,
        @Nullable UUID locationId,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.backorder.created";
    public static final int SCHEMA_VERSION = 2;

    public BackorderCreatedV1 {
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
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}
