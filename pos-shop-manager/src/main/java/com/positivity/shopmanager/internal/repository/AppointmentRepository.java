package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Appointment;
import com.positivity.shopmanager.internal.enums.AppointmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
     * Every BAY-lane appointment overlapping a date range at one location, for {@code GET
     * /v1/schedules/capacity} (issue #2023 AC7). One query for the whole requested range — the
     * caller must not loop this per day, which would just move the day-per-call fan-out this
     * endpoint exists to remove server-side.
     *
     * <p>CANCELLED appointments are excluded in SQL rather than filtered in memory (contrast {@code
     * getScheduleView}, which pulls every status and filters afterwards): a cancelled appointment
     * does not hold a bay, and there is no reason to pull rows across the wire that occupancy must
     * then discard. Unassigned appointments (no {@code resourceType}) hold no specific bay and are
     * excluded by the {@code resourceType = 'BAY'} predicate, not by a later join.
     */
    @Query("""
                        SELECT appointment
                        FROM Appointment appointment
                        WHERE appointment.locationId = :locationId
                          AND appointment.resourceType = 'BAY'
                          AND appointment.status <> 'CANCELLED'
                          AND appointment.startAt < :rangeEnd
                          AND appointment.endAt > :rangeStart
                        """)
    List<Appointment> findBayAppointmentsForCapacity(UUID locationId, Instant rangeEnd, Instant rangeStart);
}
