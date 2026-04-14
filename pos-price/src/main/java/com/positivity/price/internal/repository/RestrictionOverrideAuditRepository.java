package com.positivity.price.internal.repository;

import com.positivity.price.internal.entity.RestrictionOverrideAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RestrictionOverrideAuditRepository extends JpaRepository<RestrictionOverrideAudit, UUID> {}
