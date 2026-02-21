package com.positivity.inventory.internal.event;

import org.jspecify.annotations.NonNull;

/**
 * Aggregate root identity embedded in every {@link InventoryAuditEvent}.
 *
 * @param aggregateId   unique identifier of the aggregate root (UUIDv7 string)
 * @param aggregateType short type name, e.g. {@code "CycleCountAdjustment"}
 */
public record AuditAggregateRef(
        @NonNull String aggregateId,
        @NonNull String aggregateType) {
}
