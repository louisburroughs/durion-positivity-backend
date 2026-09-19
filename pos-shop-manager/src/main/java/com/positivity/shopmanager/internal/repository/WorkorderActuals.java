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
     *
     * <p><strong>When the current mapping has not replicated (issue #2089).</strong> The candidates
     * handed to this method come from an inner join against {@code ExtWorkorderReplica}, so a
     * mapping whose {@code workorder.events.v1} fact has not landed yet contributes no candidate at
     * all. The rule above therefore resolves the greatest {@code workOrderId} <em>among the
     * mappings that have replicated</em>, not among all mappings: while a reopened work order's
     * replica is in flight, the superseded run's actuals are returned, and the caller cannot
     * distinguish that from an appointment whose only work order is the older one.
     *
     * <p>That fallback is deliberate, not an accident of the join (#2089, option 1). The actuals it
     * reports are real events that really happened on that appointment's bay, the exposure is
     * bounded by replication lag on a single event, and the alternatives are worse: refusing to
     * resolve anything until the newest mapping replicates would make a genuinely running job's
     * overrun vanish from capacity during the lag window (the case #2050 added the actuals arm
     * for), and flagging the staleness on this record needs a product decision about what each
     * caller does with the flag. Readers that bill against these numbers should revisit the choice
     * rather than assume freshness; both callers today
     * ({@code ScheduleCapacityServiceImpl#resolveActuals} and {@code
     * AppointmentsServiceImpl#resolveWorkorderActuals}) accept it identically, because both reduce
     * the same query's rows through this method.
     */
    public static WorkorderActuals mostCurrent(WorkorderActuals a, WorkorderActuals b) {
        return a.workOrderId().compareTo(b.workOrderId()) >= 0 ? a : b;
    }
}
