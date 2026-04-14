package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntryAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TimeEntryAuditRepository extends JpaRepository<TimeEntryAudit, UUID> {}
