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
 * @param backorderId backorder record identifier (aggregate id of the fact)
 * @param workorderLineId workorder line whose demand was short
 * @param sku stock-item identifier that was short (ledger stock-item string)
 * @param quantityShort quantity that could not be fulfilled (positive)
 * @param occurredAt when the backorder was opened
 */
public record BackorderCreatedV1(
        @NonNull UUID backorderId,
        @NonNull UUID workorderLineId,
        @NonNull String sku,
        int quantityShort,
        @Nullable UUID locationId,
        @NonNull Instant occurredAt) {

    public static final String EVENT_TYPE = "inventory.backorder.created";
    public static final int SCHEMA_VERSION = 1;

    public BackorderCreatedV1 {
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
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt must not be null");
        }
    }
}
