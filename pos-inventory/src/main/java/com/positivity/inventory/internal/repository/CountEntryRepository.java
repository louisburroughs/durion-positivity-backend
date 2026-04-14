package com.positivity.inventory.internal.repository;

import com.positivity.inventory.internal.entity.CountEntry;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Repository for CountEntry entities.
 */
@Repository
public interface CountEntryRepository extends JpaRepository<CountEntry, UUID> {

    /**
     * Find all count entries for a specific cycle count task.
     * Ordered by recount sequence number.
     */
    List<CountEntry> findByCycleCountTask_TaskIdOrderByRecountSequenceNumberAsc(UUID cycleCountTaskId);

    /**
     * Find the latest count entry for a task (highest sequence number).
     */
    Optional<CountEntry> findFirstByCycleCountTask_TaskIdOrderByRecountSequenceNumberDesc(UUID taskId);

    /**
     * Count the number of entries for a specific task.
     */
    long countByCycleCountTask_TaskId(UUID cycleCountTaskId);
}
