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
     * <p><strong>Two lower bounds, not one.</strong> The predicate matches on the <em>planned</em>
     * columns, but occupancy is computed from the effective window — the linked workorder's actuals
     * when known (#2021). A job planned entirely before the requested range can still be running
     * inside it, so a lower bound of {@code from} on {@code endAt} alone silently drops it and the
     * overrun never appears (#2050). The lower edge is therefore a disjunction of two complementary
     * arms, and a row is fetched when either holds:
     *
     * <ul>
     *   <li><em>Planned arm</em> — {@code appointment.endAt > :windowStart}, where the caller passes
     *       {@code from - ScheduleCapacityServiceImpl.CARRY_OVER_LOOKBACK_DAYS} and discards the
     *       pre-range days it assembles from the extra rows. This is the arm that still catches a job
     *       which <em>completed before the range began</em> and whose overrun is being distributed
     *       forward: such a job's actuals end before {@code :rangeStart}, so the actuals arm rejects
     *       it, and only its planned window can reach back far enough to find it.
     *   <li><em>Actuals arm</em> — an {@code EXISTS} over this module's own {@code
     *       work_order_appointment_mapping} joined to its own {@code ext_workorder} replica, true when
     *       the appointment's <em>current</em> mapping (see below) names a workorder that has actually
     *       started ({@code workStartedAt IS NOT NULL}) and was still running when the range began
     *       ({@code completedAt IS NULL}, or a {@code completedAt} after {@code :rangeStart}). This is
     *       the arm that catches a job whose planned window is older than the lookback but whose
     *       actuals reach into the requested range.
     * </ul>
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
     * strength of it. {@code :rangeStart} is the requested range's own start, <em>not</em> {@code
     * :windowStart}: for a job that did finish, it asks "was the work still running when this range
     * began". See {@code ScheduleCapacityServiceImpl#CARRY_OVER_LOOKBACK_DAYS} for what the pair of
     * arms does and does not guarantee about two requests agreeing on a shared date — this narrows
     * the residual, it does not erase it.
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
                          AND appointment.startAt < :rangeEnd
                          AND (appointment.endAt > :windowStart
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
