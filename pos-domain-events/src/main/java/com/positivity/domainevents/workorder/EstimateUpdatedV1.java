package com.positivity.domainevents.workorder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: an estimate was created or changed (order parity story E1, #1077).
 *
 * <p>Published by pos-workorder on {@code workorder.events.v1} with
 * {@code eventType = "workorder.estimate.updated"} — one snapshot per business transaction that
 * touched the estimate or its items, mirroring {@link WorkorderUpdatedV1}. First consumer:
 * pos-order's {@code ext_estimate} replica feeding source-document import (spec R7.1 —
 * approved-item prices are contractual, imported without repricing).
 *
 * @param estimateId estimate identifier (also the envelope aggregateId)
 * @param estimateNumber human-readable estimate number
 * @param status owner {@code EstimateStatus} name, e.g. {@code APPROVED}
 * @param locationId shop location
 * @param customerId owning customer party
 * @param vehicleId serviced vehicle
 * @param subtotal estimate subtotal
 * @param taxAmount estimate tax
 * @param total estimate total
 * @param items current items (full replacement set on every fact)
 * @param createdAt owner row creation timestamp
 * @param updatedAt owner row last-update timestamp
 */
public record EstimateUpdatedV1(
        @NonNull UUID estimateId,
        @Nullable String estimateNumber,
        @Nullable String status,
        @Nullable UUID locationId,
        @Nullable UUID customerId,
        @Nullable UUID vehicleId,
        @Nullable BigDecimal subtotal,
        @Nullable BigDecimal taxAmount,
        @Nullable BigDecimal total,
        @Nullable List<Item> items,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "workorder.estimate.updated";
    public static final int SCHEMA_VERSION = 1;

    /**
     * One estimate item.
     *
     * @param estimateItemId item identifier
     * @param itemType owner {@code EstimateItemType} name: {@code PART} or {@code LABOR}
     * @param description item description
     * @param quantity quantity (hours for LABOR)
     * @param unitPrice unit price (hourly rate for LABOR)
     * @param lineTotal quantity × unitPrice
     * @param productId pos-catalog product reference (PART items)
     * @param serviceId pos-catalog service reference (LABOR items)
     * @param approvalStatus owner {@code ApprovalStatus} name — only {@code APPROVED} items are
     *     contractual (spec R7.1)
     * @param deleted soft-delete flag
     */
    public record Item(
            @NonNull UUID estimateItemId,
            @Nullable String itemType,
            @Nullable String description,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal unitPrice,
            @Nullable BigDecimal lineTotal,
            @Nullable UUID productId,
            @Nullable UUID serviceId,
            @Nullable String approvalStatus,
            @Nullable Boolean deleted) {}

    public EstimateUpdatedV1 {
        if (estimateId == null) {
            throw new IllegalArgumentException("estimateId must not be null");
        }
    }
}
