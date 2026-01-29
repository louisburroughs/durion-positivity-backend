package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntryAdjustment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TimeEntryAdjustmentRepository extends JpaRepository<TimeEntryAdjustment, UUID> {
    java.util.List<TimeEntryAdjustment> findByTimeEntryId(String timeEntryId);
}
