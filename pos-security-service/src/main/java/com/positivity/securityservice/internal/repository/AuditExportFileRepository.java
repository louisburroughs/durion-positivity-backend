package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.AuditExportFile;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped files produced by completed audit export jobs (#2408). */
public interface AuditExportFileRepository extends JpaRepository<AuditExportFile, UUID> {

    @NonNull
    Optional<AuditExportFile> findByJobId(@NonNull UUID jobId);

    /**
     * Deletes the files of the bound tenant's jobs that {@link
     * AuditExportJobRepository#deleteExpired(Instant)} is about to remove; run first, in the same
     * transaction, so the purge never depends on the database cascade.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            DELETE FROM AuditExportFile f
             WHERE f.jobId IN (SELECT j.jobId FROM AuditExportJob j
                                WHERE COALESCE(j.completedAt, j.requestedAt) < :cutoff)
            """)
    int deleteForExpiredJobs(@NonNull @Param("cutoff") Instant cutoff);
}
