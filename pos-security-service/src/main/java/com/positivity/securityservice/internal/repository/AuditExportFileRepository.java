package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.AuditExportFile;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Tenant-scoped files produced by completed audit export jobs (#2408). */
public interface AuditExportFileRepository extends JpaRepository<AuditExportFile, UUID> {

    Optional<AuditExportFile> findByJobId(UUID jobId);
}
