package com.positivity.domainevents.workorder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a workorder was created or changed (ADR-0044 §6, issue #897 Phase 5.1).
 *
 * <p>Published by pos-workorder on {@code workorder.events.v1} with
 * {@code eventType = "workorder.workorder.updated"} — one snapshot per business transaction that
 * touched the workorder or its part lines. Consumers maintain read-only {@code ext_workorder}
 * replicas serving putaway/receiving line validation (pos-inventory: status + part
 * productEntityId) and invoice search (pos-invoice: workorder-number lookup and enrichment).
 *
 * @param workorderId workorder identifier (also the envelope aggregateId)
 * @param workorderNumber human-readable workorder number (null until assigned)
 * @param status owner {@code WorkorderStatus} name, e.g. {@code IN_PROGRESS}
 * @param shopId shop location the workorder executes at
 * @param customerId owning customer party
 * @param invoiceId generated invoice back-reference (null until invoiced)
 * @param parts current part lines (full replacement set on every fact)
 * @param createdAt owner row creation timestamp
 * @param updatedAt owner row last-update timestamp
 */
public record WorkorderUpdatedV1(
        @NonNull UUID workorderId,
        @Nullable String workorderNumber,
        @Nullable String status,
        @Nullable UUID shopId,
        @Nullable UUID customerId,
        @Nullable UUID invoiceId,
        @Nullable List<PartLine> parts,
        @Nullable Instant createdAt,
        @Nullable Instant updatedAt) {

    public static final String EVENT_TYPE = "workorder.workorder.updated";
    public static final int SCHEMA_VERSION = 1;

    /**
     * One workorder part line.
     *
     * @param workorderLineId part line identifier
     * @param productEntityId pos-catalog product reference (null for non-inventory lines)
     * @param quantity snapshotted quantity
     */
    public record PartLine(
            @NonNull UUID workorderLineId,
            @Nullable UUID productEntityId,
            @Nullable BigDecimal quantity) {}

    public WorkorderUpdatedV1 {
        if (workorderId == null) {
            throw new IllegalArgumentException("workorderId must not be null");
        }
    }
}
