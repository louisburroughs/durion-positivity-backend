package com.positivity.inventory.repository;

import com.positivity.inventory.entity.CycleCountTask;
import com.positivity.inventory.entity.TaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for CycleCountTask entities.
 */
@Repository
public interface CycleCountTaskRepository extends JpaRepository<CycleCountTask, UUID> {
    
    /**
     * Find all tasks assigned to a specific auditor.
     */
    List<CycleCountTask> findByAuditorId(String auditorId);
    
    /**
     * Find all tasks with a specific status.
     */
    List<CycleCountTask> findByStatus(TaskStatus status);
    
    /**
     * Find tasks assigned to an auditor with a specific status.
     */
    List<CycleCountTask> findByAuditorIdAndStatus(String auditorId, TaskStatus status);
}
