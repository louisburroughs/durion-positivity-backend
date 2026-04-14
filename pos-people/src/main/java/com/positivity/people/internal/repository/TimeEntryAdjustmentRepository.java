package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntryAdjustment;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TimeEntryAdjustmentRepository extends JpaRepository<TimeEntryAdjustment, UUID> {

    java.util.List<TimeEntryAdjustment> findByTimeEntry_TimeEntryId(UUID timeEntryId);
}
