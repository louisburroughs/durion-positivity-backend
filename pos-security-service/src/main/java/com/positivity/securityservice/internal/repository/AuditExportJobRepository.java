package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.AuditExportJob;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Tenant-scoped audit export jobs (#2408). Lookups go through derived queries so Hibernate's
 * tenant predicate applies on top of row-level security: another tenant's job id is simply absent.
 */
public interface AuditExportJobRepository extends JpaRepository<AuditExportJob, UUID> {

    Optional<AuditExportJob> findByJobId(UUID jobId);
}
