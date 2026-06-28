package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderPartAdjustmentEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for querying part adjustment event history.
 */
public interface WorkorderPartAdjustmentEventRepository extends JpaRepository<WorkorderPartAdjustmentEvent, UUID> {

    /**
     * Find all adjustment events for a workorder, ordered newest first.
     *
     * @param workorderId the workorder ID
     * @return list of adjustment events (newest first)
     */
    List<WorkorderPartAdjustmentEvent> findByWorkorderIdOrderByPerformedAtDesc(UUID workorderId);

    /**
     * Find all adjustment events for a specific part, ordered newest first.
     *
     * @param partId the part ID
     * @return list of adjustment events (newest first)
     */
    List<WorkorderPartAdjustmentEvent> findByOriginalPartIdOrderByPerformedAtDesc(UUID partId);
}
