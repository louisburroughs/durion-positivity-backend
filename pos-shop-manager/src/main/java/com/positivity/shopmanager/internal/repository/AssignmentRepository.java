package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Assignment;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {
    List<Assignment> findByAppointmentId(@NonNull UUID appointmentId);
}
