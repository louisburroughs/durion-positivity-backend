package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkOrderPartSubstitution;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for immutable workorder part substitutions.
 *
 * Issue: #49
 */
public interface WorkOrderPartSubstitutionRepository extends JpaRepository<WorkOrderPartSubstitution, UUID> {}
