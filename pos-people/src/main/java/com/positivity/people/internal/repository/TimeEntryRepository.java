package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TimeEntryRepository extends JpaRepository<TimeEntry, String> {
    List<TimeEntry> findByTimeEntryIdIn(List<String> ids);
}
