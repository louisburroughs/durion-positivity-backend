package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.AssignmentMechanic;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface AssignmentMechanicRepository extends JpaRepository<AssignmentMechanic, UUID> {
    List<AssignmentMechanic> findByAssignment_AssignmentId(@NonNull UUID assignmentId);

    /** One mechanic's planned time on one appointment, as ids and instants only (#2527). */
    interface MechanicAppointmentWindow {
        UUID getMechanicPersonId();

        UUID getAppointmentId();

        Instant getStartAt();

        Instant getEndAt();
    }

    /**
     * Every mechanic assigned, through {@code assignment_mechanic}, to a non-cancelled appointment at
     * the location whose planned window overlaps {@code [rangeStart, rangeEnd)} (#2527) — one
     * statement for the whole range, so the capacity read's statement count stays independent of the
     * number of days. A cancelled assignment (superseded by a re-assignment) contributes nothing.
     */
    @Query("""
                        SELECT mechanic.personId AS mechanicPersonId,
                               appointment.appointmentId AS appointmentId,
                               appointment.startAt AS startAt,
                               appointment.endAt AS endAt
                        FROM AssignmentMechanic assignmentMechanic
                        JOIN assignmentMechanic.mechanic mechanic
                        JOIN assignmentMechanic.assignment assignment
                        JOIN assignment.appointment appointment
                        WHERE appointment.locationId = :locationId
                          AND appointment.status <> 'CANCELLED'
                          AND assignment.status <> 'CANCELLED'
                          AND appointment.startAt < :rangeEnd
                          AND appointment.endAt > :rangeStart
                        """)
    @NonNull
    List<MechanicAppointmentWindow> findMechanicWindowsAtLocation(
            @NonNull UUID locationId, @NonNull Instant rangeStart, @NonNull Instant rangeEnd);
}
