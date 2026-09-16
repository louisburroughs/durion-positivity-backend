package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.SchedulingConflict;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SchedulingConflictRepository extends JpaRepository<SchedulingConflict, UUID> {
    List<SchedulingConflict> findByAppointment_AppointmentId(@NonNull UUID appointmentId);
}
