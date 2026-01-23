package com.positivity.inventory.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.positivity.inventory.entity.CountEntry;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for CountEntry entities.
 */
@Repository
public interface CountEntryRepository extends JpaRepository<CountEntry, UUID> {
    
    /**
     * Find all count entries for a specific cycle count task.
     * Ordered by recount sequence number.
     */
    List<CountEntry> findByCycleCountTaskIdOrderByRecountSequenceNumberAsc(UUID cycleCountTaskId);
    
    /**
     * Find the latest count entry for a task (highest sequence number).
     */
    @Query("SELECT ce FROM CountEntry ce WHERE ce.cycleCountTaskId = :taskId ORDER BY ce.recountSequenceNumber DESC LIMIT 1")
    Optional<CountEntry> findLatestByTaskId(UUID taskId);
    
    /**
     * Count the number of entries for a specific task.
     */
    long countByCycleCountTaskId(UUID cycleCountTaskId);
}
