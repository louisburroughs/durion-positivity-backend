package com.positivity.workorder.internal.repository;

import com.positivity.workorder.internal.entity.SubstituteAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SubstituteAuditRepository extends JpaRepository<SubstituteAudit, UUID> {}
