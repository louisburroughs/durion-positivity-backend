package com.positivity.securityservice.service;

import com.positivity.securityservice.internal.dto.AuditExportJobResponse;
import com.positivity.securityservice.internal.dto.AuditExportRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Service contract for asynchronous audit export jobs (B-4).
 */
public interface AuditExportService {

    /**
     * Submits an audit export request and returns the created job response.
     *
     * @param request export parameters including filters, format, and delivery mode
     * @return the created job reference with PENDING status
     */
    @NonNull
    AuditExportJobResponse requestExport(@NonNull AuditExportRequest request);

    /**
     * Returns the current status of a previously submitted export job.
     *
     * @param jobId the export job UUID
     * @return job status response
     * @throws jakarta.persistence.EntityNotFoundException if jobId is not found
     */
    @NonNull
    AuditExportJobResponse getExportJob(@NonNull UUID jobId);
}
