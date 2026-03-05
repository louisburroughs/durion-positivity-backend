package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntryAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface TimeEntryAuditRepository extends JpaRepository<TimeEntryAudit, UUID> {

}
