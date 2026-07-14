package com.positivity.domainevents.inventory;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a pick list was created or changed state (ADR-0044 §6, issue #901 Phase 5.5).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.pick-list.updated"} — one snapshot per pick list touched in a
 * business transaction, recomputed from the final persisted state. Consumers (pos-workorder)
 * keep an {@code ext_pick_list} replica serving the pick-flow read path that previously called
 * the retired {@code InventoryPickClient}.
 *
 * @param pickListId pick list identifier (aggregate id of the fact)
 * @param workorderId workorder the pick list serves
 * @param status pick list status name (DRAFT, READY_TO_PICK, COMPLETED, CANCELLED, ...)
 * @param priority scheduling priority
 * @param dueAt optional due timestamp
 * @param createdAt creation timestamp of the pick list
 */
public record PickListUpdatedV1(
        @NonNull UUID pickListId,
        @Nullable UUID workorderId,
        @NonNull String status,
        int priority,
        @Nullable Instant dueAt,
        @Nullable Instant createdAt) {

    public static final String EVENT_TYPE = "inventory.pick-list.updated";
    public static final int SCHEMA_VERSION = 1;

    public PickListUpdatedV1 {
        if (pickListId == null) {
            throw new IllegalArgumentException("pickListId must not be null");
        }
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status must not be blank");
        }
    }
}
