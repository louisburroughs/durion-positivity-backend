package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.ServicePositionAssignment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only service-position history for a workorder (#1983).
 *
 * <p>Deliberately the same three-method shape as {@link TechnicianAssignmentRepository}: current
 * row, full history newest-first, and a batch lookup of current rows for a set of workorders.
 */
public interface ServicePositionAssignmentRepository extends JpaRepository<ServicePositionAssignment, Long> {

    @NonNull
    Optional<ServicePositionAssignment> findByWorkorder_IdAndCurrentTrue(@NonNull UUID workorderId);

    @NonNull
    List<ServicePositionAssignment> findByWorkorder_IdOrderByAssignedAtDescIdDesc(@NonNull UUID workorderId);
}
