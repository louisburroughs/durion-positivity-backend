package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import java.time.Instant;
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
     */
    @Query("""
                        SELECT appointment
                        FROM Appointment appointment
                        WHERE appointment.locationId = :locationId
                          AND appointment.status <> 'CANCELLED'
                          AND appointment.startAt < :rangeEnd
                          AND appointment.endAt > :rangeStart
                        """)
    @NonNull
    List<Appointment> findAppointmentsForCapacity(
            @NonNull UUID locationId, @NonNull Instant rangeEnd, @NonNull Instant rangeStart);
}
