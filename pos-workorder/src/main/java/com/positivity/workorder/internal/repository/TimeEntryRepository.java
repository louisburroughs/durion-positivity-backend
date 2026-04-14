package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.TimeEntry;
import com.positivity.workorder.internal.enums.TimeEntryStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID> {

    List<TimeEntry> findByPersonIdAndStatus(UUID personId, TimeEntryStatus status);

    List<TimeEntry> findByWorkOrder_IdAndStatus(UUID workOrderId, TimeEntryStatus status);
}
