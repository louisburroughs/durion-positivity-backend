package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderPartUsageEvent;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for WorkorderPartUsageEvent.
 * 
 * CAP:005 Story #158 - Parts Usage Tracking
 */
@Repository
public interface WorkorderPartUsageEventRepository extends JpaRepository<WorkorderPartUsageEvent, UUID> {

    /**
     * Find all usage events for a specific part, ordered newest first.
     */
    @NonNull
    List<WorkorderPartUsageEvent> findByWorkorderPartIdOrderByPerformedAtDesc(@NonNull UUID workorderPartId);

    /**
     * Find all usage events for a workorder, ordered newest first.
     */
    @NonNull
    List<WorkorderPartUsageEvent> findByWorkorderIdOrderByPerformedAtDesc(@NonNull UUID workorderId);
}
