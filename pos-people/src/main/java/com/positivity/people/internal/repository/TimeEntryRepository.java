package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TimeEntryRepository extends JpaRepository<TimeEntry, UUID> {
    List<TimeEntry> findByTimeEntryIdIn(List<UUID> ids);
}
