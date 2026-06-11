package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntryException;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeEntryExceptionRepository extends JpaRepository<TimeEntryException, UUID> {

    java.util.List<TimeEntryException> findByEmployeeId(String employeeId);

    java.util.List<TimeEntryException> findByTimeEntryId(String timeEntryId);
}
