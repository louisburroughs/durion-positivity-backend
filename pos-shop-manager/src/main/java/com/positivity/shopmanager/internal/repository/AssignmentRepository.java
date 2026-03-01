package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.Assignment;
import com.positivity.shopmanager.internal.enums.AssignmentStatusEnum;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, UUID> {
    List<Assignment> findByAppointmentId(@NonNull UUID appointmentId);

    Optional<Assignment> findByAppointmentIdAndStatusIn(
            @NonNull UUID appointmentId, @NonNull List<AssignmentStatusEnum> statuses);
}
