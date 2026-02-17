package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntryIssue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TimeEntryIssueRepository extends JpaRepository<TimeEntryIssue, UUID> {
    java.util.List<TimeEntryIssue> findByEmployeeId(String employeeId);

    java.util.List<TimeEntryIssue> findByTimeEntryId(String timeEntryId);
}
