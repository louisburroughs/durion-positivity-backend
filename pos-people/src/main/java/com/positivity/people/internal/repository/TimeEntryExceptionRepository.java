package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntryException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TimeEntryExceptionRepository extends JpaRepository<TimeEntryException, UUID> {

    java.util.List<TimeEntryException> findByEmployeeId(String employeeId);

    java.util.List<TimeEntryException> findByTimeEntryId(String timeEntryId);

}
