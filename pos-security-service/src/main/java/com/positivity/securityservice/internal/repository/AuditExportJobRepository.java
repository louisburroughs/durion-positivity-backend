package com.positivity.securityservice.internal.repository;

import com.positivity.securityservice.internal.entity.AuditExportJob;
import com.positivity.securityservice.internal.enums.AuditExportStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped audit export jobs (#2408). Lookups go through derived queries so Hibernate's
 * tenant predicate applies on top of row-level security: another tenant's job id is simply absent.
 *
 * <p>Every lifecycle transition is a status-conditional bulk update whose row count says whether
 * this caller won: the worker and the scheduled sweep can race on one job, and only the transition
 * whose {@code from} status still holds takes effect. Bulk updates skip the auditing listener, so
 * each one sets {@code updatedAt} itself from the shared clock.
 */
public interface AuditExportJobRepository extends JpaRepository<AuditExportJob, UUID> {

    @NonNull
    Optional<AuditExportJob> findByJobId(@NonNull UUID jobId);

    /** PENDING -> IN_PROGRESS; 1 when this caller claimed the job, 0 when it was no longer PENDING. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuditExportJob j
               SET j.status = com.positivity.securityservice.internal.enums.AuditExportStatus.IN_PROGRESS,
                   j.startedAt = :now, j.updatedAt = :now
             WHERE j.jobId = :jobId
               AND j.status = com.positivity.securityservice.internal.enums.AuditExportStatus.PENDING
            """)
    int markInProgress(@NonNull @Param("jobId") UUID jobId, @NonNull @Param("now") Instant now);

    /** IN_PROGRESS -> COMPLETED; 0 when the job was failed (for example by the stale sweep) meanwhile. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuditExportJob j
               SET j.status = com.positivity.securityservice.internal.enums.AuditExportStatus.COMPLETED,
                   j.completedAt = :now, j.rowCount = :rowCount, j.errorMessage = null, j.updatedAt = :now
             WHERE j.jobId = :jobId
               AND j.status = com.positivity.securityservice.internal.enums.AuditExportStatus.IN_PROGRESS
            """)
    int markCompleted(
            @NonNull @Param("jobId") UUID jobId, @Param("rowCount") long rowCount, @NonNull @Param("now") Instant now);

    /** {@code from} -> FAILED for one job; 0 when it had already left every {@code from} status. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuditExportJob j
               SET j.status = com.positivity.securityservice.internal.enums.AuditExportStatus.FAILED,
                   j.completedAt = :now, j.errorMessage = :message, j.updatedAt = :now
             WHERE j.jobId = :jobId
               AND j.status IN :from
            """)
    int markFailed(
            @NonNull @Param("jobId") UUID jobId,
            @NonNull @Param("from") Collection<AuditExportStatus> from,
            @NonNull @Param("message") String message,
            @NonNull @Param("now") Instant now);

    /** Fails every job of the bound tenant still in a {@code from} status and requested before {@code cutoff}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE AuditExportJob j
               SET j.status = com.positivity.securityservice.internal.enums.AuditExportStatus.FAILED,
                   j.completedAt = :now, j.errorMessage = :message, j.updatedAt = :now
             WHERE j.status IN :from
               AND j.requestedAt < :cutoff
            """)
    int failRequestedBefore(
            @NonNull @Param("from") Collection<AuditExportStatus> from,
            @NonNull @Param("cutoff") Instant cutoff,
            @NonNull @Param("message") String message,
            @NonNull @Param("now") Instant now);

    /** Deletes the bound tenant's jobs that finished (or, unfinished, were requested) before {@code cutoff}. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM AuditExportJob j WHERE COALESCE(j.completedAt, j.requestedAt) < :cutoff")
    int deleteExpired(@NonNull @Param("cutoff") Instant cutoff);
}
