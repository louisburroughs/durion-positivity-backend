package com.positivity.domainevents.workorder;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Payload for {@code workorder.job_time.recorded.v1} on {@code workorder.events.v1}
 * (ADR-0044 §6, #875).
 *
 * <p>Published by pos-workorder when a workorder completes: one fact per finalized labor entry,
 * keyed by {@code laborEntryId}. pos-people maintains an {@code ext_workorder_job_time} replica
 * from these facts and computes per-technician/location/local-date job-time totals locally
 * (timezone bucketing happens consumer-side from {@code endAtUtc}), replacing the retired
 * synchronous {@code /v1/workexec/job-time-totals} lookup.
 *
 * <p>Re-completing a reopened workorder re-emits its entries; consumers upsert by
 * {@code laborEntryId} (last write wins), so re-emission is harmless. The envelope is the
 * workorder module's {@code {eventId, eventType, occurredAtUtc, sourceService, payload}} shape.
 */
public record JobTimeRecordedV1(
        @NonNull UUID laborEntryId,
        @NonNull UUID workOrderId,
        @NonNull UUID technicianId,
        @Nullable UUID locationId,
        @NonNull Instant endAtUtc,
        int minutes) {

    public static final String EVENT_TYPE = "workorder.job_time.recorded.v1";
}
