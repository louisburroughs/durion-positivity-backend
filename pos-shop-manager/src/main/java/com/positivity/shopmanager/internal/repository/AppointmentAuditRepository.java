package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.AppointmentAudit;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppointmentAuditRepository extends JpaRepository<AppointmentAudit, UUID> {

    List<AppointmentAudit> findByAppointmentIdOrderByCreatedAtDesc(@NonNull UUID appointmentId);
}
