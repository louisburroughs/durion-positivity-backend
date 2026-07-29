package com.positivity.domainevents.workorder;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Fact: a recommended service line was declined on a workorder (FI-3, issue #1133, resolves spec
 * open question O-7).
 *
 * <p>Published by pos-workorder on {@code workorder.events.v1} with
 * {@code eventType = "workorder.service_line.declined.v1"} — one fact per declined service line,
 * emitted at workorder completion (a declined recommendation, promoted onto the workorder from
 * the estimate, carries its rejection reason). pos-customer raises exactly one
 * {@code DECLINED_SERVICE_FOLLOWUP} task per fact and exposes a "declined service in last N days"
 * segment predicate.
 *
 * <p>PII-minimal by design: the customer and vehicle ride as ids only.
 *
 * @param workorderId source workorder (also the envelope aggregateId and record key)
 * @param workorderNumber human-readable workorder number (null until assigned)
 * @param workorderLineId the declined service line
 * @param partyId owning customer party (null for a no-customer job)
 * @param vehicleId serviced vehicle (null for a no-vehicle job)
 * @param serviceEntityId pos-catalog service reference (null for ad-hoc labor)
 * @param description snapshotted line description of the declined service
 * @param declineReason why the customer declined the recommendation (null if not recorded)
 * @param declinedAt when the decline was recorded (the workorder completion time)
 */
public record WorkorderServiceLineDeclinedV1(
        @NonNull UUID workorderId,
        @Nullable String workorderNumber,
        @NonNull UUID workorderLineId,
        @Nullable UUID partyId,
        @Nullable UUID vehicleId,
        @Nullable UUID serviceEntityId,
        @Nullable String description,
        @Nullable String declineReason,
        @NonNull Instant declinedAt) {

    public static final String EVENT_TYPE = "workorder.service_line.declined.v1";
    public static final int SCHEMA_VERSION = 1;

    public WorkorderServiceLineDeclinedV1 {
        if (workorderId == null) {
            throw new IllegalArgumentException("workorderId must not be null");
        }
        if (workorderLineId == null) {
            throw new IllegalArgumentException("workorderLineId must not be null");
        }
        if (declinedAt == null) {
            throw new IllegalArgumentException("declinedAt must not be null");
        }
    }
}
