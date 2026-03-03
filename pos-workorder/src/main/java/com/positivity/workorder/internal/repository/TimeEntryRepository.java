package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.TimeEntry;
import com.positivity.workorder.internal.enums.TimeEntryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID> {

    List<TimeEntry> findByPersonIdAndStatus(UUID personId, TimeEntryStatus status);

    List<TimeEntry> findByWorkOrderIdAndStatus(UUID workOrderId, TimeEntryStatus status);
}
