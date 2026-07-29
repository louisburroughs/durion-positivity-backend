package com.positivity.domainevents.workorder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a workorder was completed (closed) — the service-history signal for CRM (FI-3, issue
 * #1133, resolves spec open question O-4).
 *
 * <p>Published by pos-workorder on {@code workorder.events.v1} with
 * {@code eventType = "workorder.service.completed.v1"} once, at workorder completion. pos-customer
 * projects it into a local service-history read model that powers segment predicates
 * (last-service age, service-due) and win-back campaigns; the workorder owns the fact and no
 * consumer re-fetches to process it.
 *
 * <p>PII-minimal by design: the customer and vehicle ride as ids only. {@code totalAmount} is the
 * sum of the completed (non-declined) service and part line totals — no monetary total is
 * persisted on the workorder itself, so it is computed at emit time.
 *
 * @param workorderId completed workorder (also the envelope aggregateId and record key)
 * @param workorderNumber human-readable workorder number (null until assigned)
 * @param partyId owning customer party (null for a no-customer job)
 * @param vehicleId serviced vehicle (null for a no-vehicle job)
 * @param shopId shop location the workorder executed at
 * @param invoiceId generated invoice back-reference (null until invoiced)
 * @param completedAt when the workorder was completed
 * @param totalAmount Σ line totals of the performed (non-declined) service and part lines
 * @param services the services performed on this workorder
 */
public record WorkorderServiceCompletedV1(
        @NonNull UUID workorderId,
        @Nullable String workorderNumber,
        @Nullable UUID partyId,
        @Nullable UUID vehicleId,
        @Nullable UUID shopId,
        @Nullable UUID invoiceId,
        @NonNull Instant completedAt,
        @Nullable BigDecimal totalAmount,
        @NonNull List<PerformedService> services) {

    public static final String EVENT_TYPE = "workorder.service.completed.v1";
    public static final int SCHEMA_VERSION = 1;

    /**
     * One performed (non-declined) service line.
     *
     * @param workorderLineId service line identifier
     * @param serviceEntityId pos-catalog service reference (null for ad-hoc labor)
     * @param description snapshotted line description
     * @param quantity snapshotted quantity (hours for labor)
     * @param unitPrice snapshotted unit price (hourly rate for labor)
     * @param lineTotal snapshotted line total = quantity × unitPrice
     */
    public record PerformedService(
            @NonNull UUID workorderLineId,
            @Nullable UUID serviceEntityId,
            @Nullable String description,
            @Nullable BigDecimal quantity,
            @Nullable BigDecimal unitPrice,
            @Nullable BigDecimal lineTotal) {}

    public WorkorderServiceCompletedV1 {
        if (workorderId == null) {
            throw new IllegalArgumentException("workorderId must not be null");
        }
        if (completedAt == null) {
            throw new IllegalArgumentException("completedAt must not be null");
        }
        if (services == null) {
            throw new IllegalArgumentException("services must not be null");
        }
    }
}
