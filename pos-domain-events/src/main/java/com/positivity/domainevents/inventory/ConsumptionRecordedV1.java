package com.positivity.domainevents.inventory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: picked items were consumed to a workorder (ADR-0044 §6, issue #901 Phase 5.5).
 *
 * <p>Published by pos-inventory on {@code inventory.events.v1} with
 * {@code eventType = "inventory.consumption.recorded"} — one fact per consumption transaction
 * (this is an occurrence, not a snapshot: it is never re-emitted with newer state). Consumers
 * (pos-workorder) accumulate {@code quantity} per pick task into the {@code ext_pick_task}
 * replica's consumed quantity.
 *
 * @param consumptionId consumption transaction identifier (aggregate id of the fact)
 * @param workorderId workorder the items were consumed to
 * @param pickListId pick list the items were picked from
 * @param totalItemsConsumed total quantity consumed in this transaction
 * @param consumedAt consumption timestamp
 * @param lines per-pick-task consumed quantities
 */
public record ConsumptionRecordedV1(
        @NonNull UUID consumptionId,
        @NonNull UUID workorderId,
        @Nullable UUID pickListId,
        int totalItemsConsumed,
        @Nullable Instant consumedAt,
        @NonNull List<Line> lines) {

    public static final String EVENT_TYPE = "inventory.consumption.recorded";
    public static final int SCHEMA_VERSION = 1;

    public ConsumptionRecordedV1 {
        if (consumptionId == null) {
            throw new IllegalArgumentException("consumptionId must not be null");
        }
        if (workorderId == null) {
            throw new IllegalArgumentException("workorderId must not be null");
        }
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /**
     * One consumed pick-task line.
     *
     * @param pickTaskId pick task the quantity was consumed from
     * @param skuId product/SKU consumed
     * @param quantity quantity consumed from this task
     */
    public record Line(@NonNull UUID pickTaskId, @Nullable UUID skuId, int quantity) {}
}
