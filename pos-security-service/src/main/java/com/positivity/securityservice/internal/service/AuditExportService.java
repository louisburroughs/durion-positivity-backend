package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.AuditExportDownload;
import com.positivity.securityservice.internal.dto.AuditExportJobResponse;
import com.positivity.securityservice.internal.dto.AuditExportRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service contract for asynchronous, tenant-scoped audit export jobs (B-4, #2408).
 */
public interface AuditExportService {

    /**
     * Persists a PENDING export job for the bound tenant and schedules it to run once the creating
     * transaction commits.
     *
     * @param request export parameters including filters, format, and delivery mode
     * @return the created job reference with PENDING status
     * @throws com.positivity.securityservice.internal.exception.AuditExportWebhookUnsupportedException
     *     when WEBHOOK delivery is requested
     */
    @NonNull
    AuditExportJobResponse requestExport(@NonNull AuditExportRequest request);

    /**
     * Returns the current status of a previously submitted export job of the bound tenant. A job
     * left PENDING or IN_PROGRESS longer than the configured timeout is marked FAILED as
     * interrupted first.
     *
     * @param jobId the export job UUID
     * @return job status response
     * @throws jakarta.persistence.EntityNotFoundException if jobId is not found for this tenant
     */
    @NonNull
    AuditExportJobResponse getExportJob(@NonNull UUID jobId);

    /**
     * Returns the file a COMPLETED export job of the bound tenant produced.
     *
     * @param jobId the export job UUID
     * @return the file name, content type and content
     * @throws jakarta.persistence.EntityNotFoundException if jobId is not found for this tenant
     * @throws com.positivity.securityservice.internal.exception.AuditExportNotReadyException if the
     *     job is not COMPLETED
     */
    @NonNull
    AuditExportDownload getExportFile(@NonNull UUID jobId);

    /**
     * Runs a PENDING job to COMPLETED or FAILED. Called on the export executor with the requesting
     * tenant bound; a job that is no longer PENDING is left alone.
     *
     * @param jobId the export job UUID
     */
    void runExport(@NonNull UUID jobId);
}
