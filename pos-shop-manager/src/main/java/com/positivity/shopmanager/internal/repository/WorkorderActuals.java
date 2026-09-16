package com.positivity.shopmanager.internal.repository;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The workorder actual-time block resolved for one appointment through {@link
 * WorkOrderAppointmentMapping} (issue #2021 F9) — the authoritative link the status sync already
 * resolves through, not {@code Appointment.workorderLinkRef} or {@code sourceType}/{@code
 * sourceId}, which are creation-time provenance only.
 *
 * <p>Sourced from {@link com.positivity.shopmanager.internal.entity.ExtWorkorderReplica}:
 * {@code workStartedAt}/{@code completedAt}/{@code expectedEndAt} are the owner's actual-time
 * block (ADR-0044 §6, #2021), never synthesised by this module.
 *
 * @param appointmentId the appointment the actuals were resolved for
 * @param workOrderId the linked workorder identifier, carried so a caller can name it (AC4)
 * @param workStartedAt actual start; null while the workorder has not started
 * @param completedAt actual finish; null while the workorder is still open
 * @param expectedEndAt the owner's projected finish; null in every fact published today (#2021 F5)
 */
public record WorkorderActuals(
        @NonNull UUID appointmentId,
        @NonNull UUID workOrderId,
        @Nullable Instant workStartedAt,
        @Nullable Instant completedAt,
        @Nullable Instant expectedEndAt) {

    /**
     * The single mapping-selection rule shared by every reader of {@code
     * WorkOrderAppointmentMapping} (issues #2023 F3 and S1): the ERD documents appointment ->
     * mapping as one-to-many (a reopened work order links a new mapping row without deleting the
     * earlier one) and only {@code work_order_id} — never {@code appointment_id} — is unique in the
     * baseline schema, so more than one row can resolve for the same appointment. A caller must not
     * take "whichever the database happens to return first": that is nondeterministic, can surface
     * a stale workorder's actuals, and can change between two identical requests.
     *
     * <p>The rule: the current mapping is the one with the numerically greatest {@code
     * workOrderId}. Every primary key on this platform is a UUIDv7 (ADR-0013/0027), which sorts by
     * creation time, so the greatest {@code workOrderId} names the most recently created mapping —
     * the reopened work order that superseded the earlier one. Both {@link
     * com.positivity.shopmanager.internal.repository.WorkOrderAppointmentMappingRepository
     * #findActualsByAppointmentIds} batch callers and any single-appointment lookup must resolve
     * duplicates through this method rather than defining their own precedence.
     */
    public static WorkorderActuals mostCurrent(WorkorderActuals a, WorkorderActuals b) {
        return a.workOrderId().compareTo(b.workOrderId()) >= 0 ? a : b;
    }
}
