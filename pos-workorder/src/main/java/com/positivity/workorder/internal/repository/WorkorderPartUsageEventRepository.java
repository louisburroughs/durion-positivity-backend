package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.WorkorderPartUsageEvent;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository for WorkorderPartUsageEvent.
 *
 * CAP:005 Story #158 - Parts Usage Tracking
 */
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
