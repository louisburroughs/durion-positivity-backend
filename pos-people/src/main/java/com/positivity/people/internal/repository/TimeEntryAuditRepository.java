package com.positivity.people.internal.repository;

import com.positivity.people.internal.entity.TimeEntryAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TimeEntryAuditRepository extends JpaRepository<TimeEntryAudit, UUID> {}
