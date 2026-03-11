package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.AppointmentAudit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppointmentAuditRepository extends JpaRepository<AppointmentAudit, UUID> {

    @Query("""
            SELECT a
            FROM AppointmentAudit a
            WHERE a.appointment.appointmentId = :appointmentId
            ORDER BY a.createdAt DESC
            """)
    List<AppointmentAudit> findByAppointmentIdOrderByCreatedAtDesc(@NonNull @Param("appointmentId") UUID appointmentId);
}
