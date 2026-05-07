package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.AuditExportJobResponse;
import com.positivity.securityservice.internal.dto.AuditExportRequest;
import com.positivity.securityservice.internal.enums.AuditExportStatus;
import com.positivity.securityservice.service.AuditExportService;
import com.positivity.time.TimeSource;
import jakarta.persistence.EntityNotFoundException;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;

/**
 * Temporary in-memory stub for audit export jobs.
 */
@Service
public class AuditExportServiceImpl implements AuditExportService {

    // TODO(B-4): Replace in-memory job store with persisted AuditExportJob
    // entity/repository.
    private final ConcurrentMap<UUID, AuditExportJobResponse> jobs = new ConcurrentHashMap<>();

    @Override
    @NonNull
    public AuditExportJobResponse requestExport(@NonNull AuditExportRequest request) {
        UUID jobId = UUID.randomUUID();
        AuditExportJobResponse response = AuditExportJobResponse.builder()
                .jobId(jobId)
                .status(AuditExportStatus.PENDING)
                .requestedAt(TimeSource.instant())
                .completedAt(null)
                .downloadUrl(null)
                .errorMessage(null)
                .build();
        jobs.put(jobId, response);
        return response;
    }

    @Override
    @NonNull
    public AuditExportJobResponse getExportJob(@NonNull UUID jobId) {
        AuditExportJobResponse response = jobs.get(jobId);
        if (response == null) {
            throw new EntityNotFoundException("Audit export job not found: " + jobId);
        }
        return response;
    }
}
