package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AppointmentRepository extends JpaRepository<Appointment, UUID> {

    Optional<Appointment> findByAppointmentIdAndStatus(UUID appointmentId, AppointmentStatus status);

    Optional<Appointment> findByIdempotencyKey(String idempotencyKey);

    @Query("""
                        SELECT appointment
                        FROM Appointment appointment
                        WHERE appointment.locationId = :locationId
                          AND appointment.startAt < :dayEndAt
                          AND appointment.endAt > :dayStartAt
                        """)
    List<Appointment> findByLocationIdAndStartAtLessThanAndEndAtGreaterThan(
            UUID locationId, Instant dayEndAt, Instant dayStartAt);

    List<Appointment> findByResourceIdAndResourceTypeAndStartAtLessThanAndEndAtGreaterThan(
            String resourceId, String resourceType, Instant windowEnd, Instant windowStart);

    /**
     * Appointments still holding {@code resourceId} for any part of {@code [startAt, endAt)} — the
     * BAY_DOUBLE_BOOKED pre-check (CAP-326). Reporting only: the exclusion constraint (V8) is the
     * enforcement, and the caller drops its own row on a reschedule.
     */
    @Query("""
                        SELECT appointment
                        FROM Appointment appointment
                        WHERE appointment.resourceId = :resourceId
                          AND appointment.status IN :held
                          AND appointment.startAt < :endAt
                          AND appointment.endAt > :startAt
                        """)
    @NonNull
    List<Appointment> findHeldOverlappingForResource(
            @NonNull String resourceId,
            @NonNull Instant startAt,
            @NonNull Instant endAt,
            @NonNull Collection<AppointmentStatus> held);

    /** Every held appointment at the location overlapping the window, whatever its resource. */
    @Query("""
                        SELECT appointment
                        FROM Appointment appointment
                        WHERE appointment.locationId = :locationId
                          AND appointment.status IN :held
                          AND appointment.startAt < :endAt
                          AND appointment.endAt > :startAt
                        """)
    @NonNull
    List<Appointment> findHeldOverlappingAtLocation(
            @NonNull UUID locationId,
            @NonNull Instant startAt,
            @NonNull Instant endAt,
            @NonNull Collection<AppointmentStatus> held);

    /**
     * Every non-cancelled appointment overlapping a date range at one location, for {@code GET
     * /v1/schedules/capacity} (issue #2023 AC7). One query for the whole requested range — the
     * caller must not loop this per day, which would just move the day-per-call fan-out this
     * endpoint exists to remove server-side.
     *
     * <p>CANCELLED appointments are excluded in SQL rather than filtered in memory (contrast {@code
     * getScheduleView}, which pulls every status and filters afterwards): a cancelled appointment
     * does not hold a bay, and there is no reason to pull rows across the wire that occupancy must
     * then discard.
     *
     * <p>This does <em>not</em> filter by {@code resourceType}: {@link Appointment#getResourceType()}
     * is never written by {@code AppointmentsServiceImpl#persistAppointment} (only
     * {@code AssignmentServiceImpl}'s {@code Assignment} entity sets it), so a predicate on that
     * column would match no real appointment and every occupancy read would report zero (#2023
     * F1). Bay membership is resolved by the caller instead, by matching each row's {@code
     * resourceId} against the active bay roster already loaded for the request — no second
     * statement, and the resource-type ambiguity (bay id, technician id, or {@code UNASSIGNED}) is
     * resolved the same way {@code /schedules/view} resolves it: by what the id actually names, not
     * by a field production never populates.
     *
     * <p><strong>Two whole windows, not one.</strong> The predicate matches on the <em>planned</em>
     * columns, but occupancy is computed from the effective window — the linked workorder's actuals
     * when known (#2021). A job planned entirely before the requested range can still be running
     * inside it, so a lower bound of {@code from} on {@code endAt} alone silently drops it and the
     * overrun never appears (#2050); a job planned entirely <em>after</em> the range can equally be
     * running inside it, because nothing constrains {@code workStartedAt} to fall on or after the
     * planned {@code startAt} (#2085). The whole time predicate is therefore a disjunction of two
     * complementary arms — each one a complete window test of its own, neither one a bound on the
     * other — and a row is fetched when either holds:
     *
     * <ul>
     *   <li><em>Planned arm</em> — {@code appointment.startAt < :rangeEnd AND appointment.endAt >
     *       :windowStart}, where the caller passes {@code from -
     *       ScheduleCapacityServiceImpl.CARRY_OVER_LOOKBACK_DAYS} as {@code :windowStart} and discards
     *       the pre-range days it assembles from the extra rows. This is the arm that still catches a
     *       job which <em>completed before the range began</em> and whose overrun is being distributed
     *       forward: such a job's actuals end before {@code :rangeStart}, so the actuals arm rejects
     *       it, and only its planned window can reach back far enough to find it. Its upper bound is
     *       {@code :rangeEnd} exactly, with no lookahead, and that is not an omission — see below.
     *   <li><em>Actuals arm</em> — an {@code EXISTS} over this module's own {@code
     *       work_order_appointment_mapping} joined to its own {@code ext_workorder} replica, true when
     *       the appointment's <em>current</em> mapping (see below) names a workorder that actually
     *       started before the range ended ({@code workStartedAt IS NOT NULL AND workStartedAt <
     *       :rangeEnd}) and was still running when the range began ({@code completedAt IS NULL}, or a
     *       {@code completedAt} after {@code :rangeStart}). This arm carries <em>both</em> edges of the
     *       widening: a job whose planned window is older than the lookback but whose actuals reach
     *       into the requested range (#2050), and a job planned after the range that has already
     *       started (#2085).
     * </ul>
     *
     * <p><strong>Why the arms are disjoined whole, and why there is no day-count lookahead (#2085
     * AC3).</strong> Before #2085 the planned upper bound {@code appointment.startAt < :rangeEnd} was
     * {@code AND}-ed over both arms, which defeated the actuals arm at the top end: a bay held since
     * Wednesday by a job planned for Friday read as free on Thursday whenever the client stopped its
     * range at Thursday. Lifting that {@code AND} is the whole fix, and it needs no new constant,
     * because the two ways a job can contribute to a day inside {@code [from, to]} are exhaustive:
     *
     * <ul>
     *   <li><em>No actuals</em> — the effective window <em>is</em> the planned window, so the job
     *       overlaps the range only if {@code plannedStart < rangeEnd}. The planned arm already has
     *       that, and it is also #2085 AC5 for free: an appointment planned after the range whose work
     *       has not started has {@code plannedStart >= rangeEnd}, fails both arms, and contributes
     *       nothing — no extra filtering, and nothing for the caller to discard.
     *   <li><em>Actuals exist</em> — the effective start is {@code workStartedAt}. For the job to
     *       overlap the range while {@code plannedStart >= rangeEnd} it must have started before the
     *       range ended and either still be running or have completed after {@code :rangeStart},
     *       which is the actuals arm verbatim.
     * </ul>
     *
     * <p>A lookahead measured in days on the planned column would therefore buy nothing a correct
     * answer needs: every row it added beyond this arm is a row AC5 requires to contribute zero. It
     * would also still be wrong at its own edge — a placeholder booked further out than the bound and
     * started early is the very defect #2085 reports, just moved further away — whereas "has the work
     * actually begun" is a fact about the job rather than about where the client put its range, so two
     * legal requests sharing a date cannot disagree about it. That is the same request-independence
     * the lower edge's actuals arm already claims, and it makes #2085 AC2 provable for a started job
     * rather than merely widened.
     *
     * <p><strong>The bound, stated plainly (#2085 AC3).</strong> The arm is bounded in rows, not in
     * days: {@code workStartedAt IS NOT NULL AND completedAt IS NULL} is the set of jobs in progress
     * at one location, which a shop's bay count bounds and which does not grow with time (the same
     * argument set out below for the lower edge, and the reason the arm is affordable at all). The
     * residual limits it does <em>not</em> erase: a job planned after the range that really did start
     * early is found only through its actuals, so one whose {@code ext_workorder} replica has not yet
     * arrived — or which has no workorder mapping at all — is invisible to this read, exactly as it is
     * to every other consumer of the replica; and a workorder left open by mistake keeps costing its
     * handful of rows until the data is corrected. Both are data-quality defects with a bounded price,
     * not unbounded scans.
     *
     * <p><strong>The actuals arm tests the current mapping, not any mapping.</strong> Appointment ->
     * mapping is one-to-many — a reopened workorder links a new mapping row without deleting the
     * earlier one, and only {@code work_order_id} is unique in the baseline schema (#2023 F3/S1) — so
     * an unrestricted {@code EXISTS} would accept a row on the strength of a mapping that is not the
     * one the caller will go on to resolve. {@link WorkorderActuals#mostCurrent} is this module's
     * single mapping-selection rule and it forbids a reader defining its own precedence, so the
     * subquery applies that rule instead of inventing one: the inner {@code NOT EXISTS} keeps only the
     * mapping with no greater {@code workOrderId} for the same appointment, which is exactly the row
     * {@code mostCurrent} reduces to. Eligibility and resolution therefore cannot disagree — an
     * appointment is fetched by this arm if and only if the actuals {@code
     * ScheduleCapacityServiceImpl#resolveActuals} will resolve for it satisfy the arm's time test.
     *
     * <p>The inner subquery <em>deliberately</em> repeats the {@code ExtWorkorderReplica} join. {@link
     * WorkOrderAppointmentMappingRepository#findActualsByAppointmentIds} joins the replica too, so a
     * mapping whose replica has not arrived yields no row there and can never win {@code mostCurrent};
     * the batch's effective rule is "the greatest {@code workOrderId} among this appointment's
     * mappings <em>that have a replica</em>", not the greatest among all of them. Dropping the join
     * here would let an unreplicated newer mapping mask the older one this arm must judge, and the two
     * sites would disagree again in the opposite direction.
     *
     * <p>Selecting by {@code NOT EXISTS ... workOrderId >} rather than by {@code MAX(workOrderId)} is
     * deliberate on two counts: PostgreSQL has no {@code max(uuid)} aggregate (the comparison
     * operators it does have are what the btree {@code uuid_ops} family provides), and a {@code >}
     * predicate is the same comparison the database already uses to order this column.
     *
     * <p><strong>Ordering caveat — the two selection sites agree, but not for free.</strong> {@code
     * mostCurrent} compares in Java with {@link java.util.UUID#compareTo}, which is a <em>signed</em>
     * comparison of the two {@code long} halves; the database compares a {@code uuid} as 16
     * <em>unsigned</em> big-endian bytes. Signed and unsigned comparison differ only when the two
     * operands' top bits differ, so the two agree here because of two properties of a UUIDv7
     * (ADR-0013/0027), not by construction: (1) the most-significant half begins with the 48-bit
     * {@code unix_ts_ms}, whose top bit stays 0 until {@code 2^47} ms after the epoch — the year 6429
     * — so for any realistic timestamp both operands' high halves are non-negative and compare
     * identically either way; and (2) the least-significant half, which only decides ties within the
     * same millisecond and random block, always carries the RFC 9562 variant bits {@code 10} in its
     * top two bits, so both operands have that bit set and their signed and unsigned order again
     * coincide. Should either property stop holding, these two sites would have to be reconciled
     * explicitly.
     *
     * <p>The actuals arm was once rejected here, on the grounds that {@code completedAt IS NULL} has
     * no lower bound in time and so a workorder started and never closed would be refetched forever.
     * That reasoning is wrong, and it is worth correcting explicitly rather than deleting: it
     * conflated "no bound in time" with "unbounded rows". {@code workStartedAt IS NOT NULL AND
     * completedAt IS NULL} is precisely the set of jobs <em>in progress</em> at the location, which
     * is bounded by how many bays and open jobs a shop has — operationally small, and it does not
     * grow with time. A workorder left open by mistake costs a handful of rows: a data-quality defect
     * with a bounded price, not an unbounded scan.
     *
     * <p>What the arm buys is that it is <em>request-independent</em> for a job in progress — whether
     * a job has started and not yet finished does not depend on where the client put its range start,
     * so two legal requests sharing a date both fetch it and cannot disagree about that date on the
     * strength of it. The arm's two request-relative instants are deliberately the <em>requested</em>
     * range's own edges, not the assembly window's: {@code :rangeStart}, <em>not</em> {@code
     * :windowStart}, asks of a job that did finish "was the work still running when this range
     * began"; {@code :rangeEnd} asks "had the work begun before this range ended", which is what
     * keeps a job whose work starts after every day on the board out of the result (#2085 AC5)
     * without re-imposing a bound on where its planned window sits. See {@code
     * ScheduleCapacityServiceImpl#CARRY_OVER_LOOKBACK_DAYS} for what the pair of arms does and does
     * not guarantee about two requests agreeing on a shared date — this narrows the residual, it
     * does not erase it.
     *
     * <p>This remains <em>one statement</em>. The {@code EXISTS} is a subquery of the same select,
     * not a second repository call, so the fixed statement budget of {@code GET
     * /v1/schedules/capacity} is unchanged.
     *
     * <p>Access path. {@code appointment} has one explicitly created index, {@code
     * appointment_tenant_idx ON public.appointment USING btree (tenant_id)} ({@code
     * V1__baseline_shop_manager.sql:662}); its only other indexes are constraint-backed — {@code
     * appointment_pkey} on {@code (appointment_id)}, {@code appointment_tenant_key} on {@code
     * (tenant_id, appointment_id)}, and V8's GiST exclusion index, which needs a {@code resource_id}
     * equality this predicate does not have. V2-V10 add no further index on this table. There is no
     * {@code (location_id, start_at, end_at)} index, so this was a tenant-index scan with a time
     * filter before the disjunction and it is a tenant-index scan with a time filter after: the
     * {@code OR} changes which rows come back, not what drives the scan. The {@code EXISTS} adds a
     * semi-join over {@code work_order_appointment_mapping}, whose {@code appointment_id} carries a
     * foreign key but no index of its own (PostgreSQL does not index a referencing column
     * automatically), so the planner is free to hash the mapping side once rather than probe it per
     * row — that table holds one row per workorder link, the same order of magnitude as the
     * appointments it is being joined to.
     */
    @Query("""
                        SELECT appointment
                        FROM Appointment appointment
                        WHERE appointment.locationId = :locationId
                          AND appointment.status <> 'CANCELLED'
                          AND ((appointment.startAt < :rangeEnd
                                AND appointment.endAt > :windowStart)
                               OR EXISTS (SELECT 1
                                          FROM WorkOrderAppointmentMapping mapping
                                          JOIN ExtWorkorderReplica workorder
                                            ON workorder.workorderId = mapping.workOrderId
                                          WHERE mapping.appointment = appointment
                                            AND NOT EXISTS (SELECT 1
                                                            FROM WorkOrderAppointmentMapping newerMapping
                                                            JOIN ExtWorkorderReplica newerWorkorder
                                                              ON newerWorkorder.workorderId = newerMapping.workOrderId
                                                            WHERE newerMapping.appointment = appointment
                                                              AND newerMapping.workOrderId > mapping.workOrderId)
                                            AND workorder.workStartedAt IS NOT NULL
                                            AND workorder.workStartedAt < :rangeEnd
                                            AND (workorder.completedAt IS NULL
                                                 OR workorder.completedAt > :rangeStart)))
                        """)
    @NonNull
    List<Appointment> findAppointmentsForCapacity(
            @NonNull UUID locationId,
            @NonNull Instant rangeEnd,
            @NonNull Instant windowStart,
            @NonNull Instant rangeStart);
}
