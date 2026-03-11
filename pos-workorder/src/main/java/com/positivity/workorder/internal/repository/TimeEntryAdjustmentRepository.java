package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.TimeEntryAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TimeEntryAdjustmentRepository extends JpaRepository<TimeEntryAdjustment, UUID> {

    List<TimeEntryAdjustment> findByTimeEntry_TimeEntryId(UUID timeEntryId);
}
