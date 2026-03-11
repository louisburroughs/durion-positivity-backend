package com.positivity.shopmanager.internal.repository;

import com.positivity.shopmanager.internal.entity.AssignmentMechanic;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentMechanicRepository extends JpaRepository<AssignmentMechanic, UUID> {
    List<AssignmentMechanic> findByAssignment_AssignmentId(@NonNull UUID assignmentId);
}
