package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.TechnicianAssignment;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for managing technician assignments to workorders.
 */
@Repository
public interface TechnicianAssignmentRepository extends JpaRepository<TechnicianAssignment, Long> {
    
    /**
     * Find the current assignment for a workorder.
     * 
     * @param workorderId the workorder ID
     * @return optional containing the current assignment if one exists
     */
    @NonNull
    Optional<TechnicianAssignment> findByWorkorderIdAndCurrentTrue(@NonNull UUID workorderId);
    
    /**
     * Find all assignments for a workorder ordered by most recent first.
     * 
     * @param workorderId the workorder ID
     * @return list of assignments ordered by assignedAt descending
     */
    @NonNull
    List<TechnicianAssignment> findByWorkorderIdOrderByAssignedAtDesc(@NonNull UUID workorderId);
}
