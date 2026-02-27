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

    @Query("""
            SELECT appointment
            FROM Appointment appointment
            WHERE appointment.locationId = :locationId
              AND appointment.startAt < :dayEndAt
              AND appointment.endAt > :dayStartAt
            """)
    List<Appointment> findByLocationIdAndStartAtLessThanAndEndAtGreaterThan(
            UUID locationId,
            Instant dayEndAt,
            Instant dayStartAt);
}
