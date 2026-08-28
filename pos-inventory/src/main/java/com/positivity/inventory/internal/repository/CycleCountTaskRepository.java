package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CycleCountTask;
import com.positivity.inventory.internal.enums.TaskStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for CycleCountTask entities.
 */
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

    /**
     * Tasks generated from one cycle count plan, in creation order (createdAt
     * with the time-ordered UUIDv7 taskId as tiebreaker) — the order the plan
     * task listing endpoint documents.
     */
    @NonNull
    List<CycleCountTask> findByPlanIdOrderByCreatedAtAscTaskIdAsc(@NonNull UUID planId);
}
