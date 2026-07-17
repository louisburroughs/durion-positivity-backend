package com.positivity.domainevents.inventory;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a pick task was created or changed state (ADR-0044 §6, issue #901 Phase 5.5).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.pick-task.updated"} — one snapshot per pick task touched in a
 * business transaction. Consumers (pos-workorder) keep an {@code ext_pick_task} replica serving
 * scan resolution and pick-progress reads.
 *
 * @param pickTaskId pick task identifier (aggregate id of the fact)
 * @param pickListId owning pick list
 * @param workorderId workorder the owning pick list serves
 * @param skuId product/SKU to pick (pos-inventory {@code productId})
 * @param locationId suggested (or scanned) storage location
 * @param quantityRequired quantity to pick
 * @param quantityPicked quantity picked so far
 * @param status pick task status name (PENDING, PICKED, ...)
 * @param sortOrder pick route ordering
 */
public record PickTaskUpdatedV1(
        @NonNull UUID pickTaskId,
        @Nullable UUID pickListId,
        @Nullable UUID workorderId,
        @Nullable UUID skuId,
        @Nullable UUID locationId,
        int quantityRequired,
        int quantityPicked,
        @NonNull String status,
        int sortOrder) {

    public static final String EVENT_TYPE = "inventory.pick-task.updated";
    public static final int SCHEMA_VERSION = 1;

    public PickTaskUpdatedV1 {
        if (pickTaskId == null) {
            throw new IllegalArgumentException("pickTaskId must not be null");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
    }
}
