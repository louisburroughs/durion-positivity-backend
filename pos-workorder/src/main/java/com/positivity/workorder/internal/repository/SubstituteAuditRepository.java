package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.SubstituteAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface SubstituteAuditRepository extends JpaRepository<SubstituteAudit, UUID> {
}