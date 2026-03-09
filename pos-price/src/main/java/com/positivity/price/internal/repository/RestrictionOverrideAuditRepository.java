package com.positivity.price.internal.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.positivity.price.internal.entity.RestrictionOverrideAudit;

public interface RestrictionOverrideAuditRepository extends JpaRepository<RestrictionOverrideAudit, UUID> {
}
