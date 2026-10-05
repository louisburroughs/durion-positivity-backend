package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.config.AuditExportConfig;
import com.positivity.securityservice.internal.config.AuditExportProperties;
import com.positivity.securityservice.internal.dto.AuditEventSearchFilter;
import com.positivity.securityservice.internal.dto.AuditExportDownload;
import com.positivity.securityservice.internal.dto.AuditExportJobResponse;
import com.positivity.securityservice.internal.dto.AuditExportRequest;
import com.positivity.securityservice.internal.dto.AuditLogEventDto;
import com.positivity.securityservice.internal.entity.AuditExportFile;
import com.positivity.securityservice.internal.entity.AuditExportJob;
import com.positivity.securityservice.internal.entity.AuditLogEvent;
import com.positivity.securityservice.internal.enums.AuditDeliveryMode;
import com.positivity.securityservice.internal.enums.AuditExportFormat;
import com.positivity.securityservice.internal.enums.AuditExportStatus;
import com.positivity.securityservice.internal.exception.AuditExportNotReadyException;
import com.positivity.securityservice.internal.exception.AuditExportWebhookUnsupportedException;
import com.positivity.securityservice.internal.repository.AuditExportFileRepository;
import com.positivity.securityservice.internal.repository.AuditExportJobRepository;
import com.positivity.securityservice.internal.repository.AuditLogEventRepository;
import jakarta.persistence.EntityNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * Persisted, tenant-scoped audit export jobs (B-4, #2408).
 *
 * <p>A request writes a PENDING {@link AuditExportJob} and, once that transaction commits, hands
 * the job to the {@link AuditExportConfig#EXECUTOR_BEAN} executor, whose task decorator carries the
 * requesting tenant to the worker. The worker claims the job (IN_PROGRESS), reads the matching audit
 * events through the same filter path as {@code searchAuditEvents}, renders CSV or JSON, and stores
 * the file with the job (COMPLETED) — or records a sanitised reason (FAILED). Every step runs in its
 * own transaction so a failure never hides the job's state. Work in flight when the service stops is
 * lost; a read past {@link AuditExportProperties#staleAfter()} marks such a job FAILED.
 */
@Slf4j
@Service
public class AuditExportServiceImpl implements AuditExportService {

    static final String DOWNLOAD_PATH_TEMPLATE = "/security-service/v1/audit/exports/%s/download";
    static final String CSV_CONTENT_TYPE = "text/csv";
    static final String JSON_CONTENT_TYPE = "application/json";
    static final String INTERRUPTED_MESSAGE =
            "The export was interrupted before it completed (for example by a service restart); request a new export.";
    static final String GENERIC_FAILURE_MESSAGE =
            "The export failed while reading or writing audit events; request a new export, or contact support with the job id.";
    static final String QUEUE_FULL_MESSAGE = "The export queue is full; request the export again later.";
    private static final int ERROR_MESSAGE_MAX = 500;

    private final AuditExportJobRepository jobRepository;
    private final AuditExportFileRepository fileRepository;
    private final AuditLogEventRepository auditLogEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final AuditExportProperties properties;
    private final Executor executor;
    private final TransactionTemplate writeTx;
    private final TransactionTemplate readTx;

    public AuditExportServiceImpl(
            AuditExportJobRepository jobRepository,
            AuditExportFileRepository fileRepository,
            AuditLogEventRepository auditLogEventRepository,
            ObjectMapper objectMapper,
            Clock clock,
            AuditExportProperties properties,
            @Qualifier(AuditExportConfig.EXECUTOR_BEAN) Executor executor,
            PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.fileRepository = fileRepository;
        this.auditLogEventRepository = auditLogEventRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.properties = properties;
        this.executor = executor;
        // REQUIRES_NEW: the worker steps and the after-commit failure path must never join a
        // transaction still bound to the submitting thread.
        this.writeTx = new TransactionTemplate(transactionManager);
        this.writeTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx = new TransactionTemplate(transactionManager);
        this.readTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.readTx.setReadOnly(true);
    }

    @Override
    @NonNull
    @Transactional
    public AuditExportJobResponse requestExport(@NonNull AuditExportRequest request) {
        if (request.getDeliveryMode() == AuditDeliveryMode.WEBHOOK) {
            throw new AuditExportWebhookUnsupportedException();
        }
        AuditEventSearchFilter filter =
                request.getFilters() == null ? new AuditEventSearchFilter() : request.getFilters();
        AuditEventQueries.validate(filter);

        AuditExportJob job = new AuditExportJob();
        job.setStatus(AuditExportStatus.PENDING);
        job.setFormat(request.getFormat());
        job.setDeliveryMode(request.getDeliveryMode());
        job.setFilterFromDate(filter.getFromDate());
        job.setFilterToDate(filter.getToDate());
        job.setFilterEventType(blankToNull(filter.getEventType()));
        job.setFilterActorId(blankToNull(filter.getActorId()));
        job.setFilterAggregateId(blankToNull(filter.getAggregateId()));
        job.setRequestedAt(clock.instant());
        AuditExportJob saved = jobRepository.save(job);
        AuditExportJobResponse response = toResponse(saved);

        UUID jobId = saved.getJobId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(jobId);
                }
            });
        } else {
            dispatch(jobId);
        }
        return response;
    }

    @Override
    @NonNull
    @Transactional
    public AuditExportJobResponse getExportJob(@NonNull UUID jobId) {
        AuditExportJob job = findJob(jobId);
        failIfStale(job);
        return toResponse(job);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public AuditExportDownload getExportFile(@NonNull UUID jobId) {
        AuditExportJob job = findJob(jobId);
        if (job.getStatus() != AuditExportStatus.COMPLETED) {
            throw new AuditExportNotReadyException(jobId, job.getStatus());
        }
        AuditExportFile file = fileRepository
                .findByJobId(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Audit export file not found for job: " + jobId));
        return new AuditExportDownload(
                file.getFileName(), file.getContentType(), file.getContent().getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void runExport(@NonNull UUID jobId) {
        Claim claim = writeTx.execute(status -> claim(jobId));
        if (claim == null) {
            return;
        }
        try {
            Rendered rendered = readTx.execute(status -> render(jobId, claim));
            if (rendered == null) {
                throw new IllegalStateException("Export rendering produced nothing");
            }
            writeTx.executeWithoutResult(status -> complete(jobId, rendered));
        } catch (ExportTooLargeException e) {
            log.warn("Audit export {} refused: {}", jobId, e.getMessage());
            markFailed(jobId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Audit export {} failed", jobId, e);
            markFailed(jobId, GENERIC_FAILURE_MESSAGE);
        }
    }

    private void dispatch(UUID jobId) {
        try {
            executor.execute(() -> runExport(jobId));
        } catch (RejectedExecutionException e) {
            log.warn("Audit export {} rejected by the export executor", jobId, e);
            markFailed(jobId, QUEUE_FULL_MESSAGE);
        }
    }

    private Claim claim(UUID jobId) {
        AuditExportJob job = jobRepository.findByJobId(jobId).orElse(null);
        if (job == null || job.getStatus() != AuditExportStatus.PENDING) {
            return null;
        }
        job.setStatus(AuditExportStatus.IN_PROGRESS);
        job.setStartedAt(clock.instant());
        jobRepository.save(job);
        AuditEventSearchFilter filter = AuditEventSearchFilter.builder()
                .fromDate(job.getFilterFromDate())
                .toDate(job.getFilterToDate())
                .eventType(job.getFilterEventType())
                .actorId(job.getFilterActorId())
                .aggregateId(job.getFilterAggregateId())
                .build();
        return new Claim(filter, job.getFormat());
    }

    private Rendered render(UUID jobId, Claim claim) {
        Specification<AuditLogEvent> specification = AuditEventQueries.specification(claim.filter());
        long matching = auditLogEventRepository.count(specification);
        if (matching > properties.maxRows()) {
            throw new ExportTooLargeException(matching, properties.maxRows());
        }
        List<AuditLogEventDto> events =
                auditLogEventRepository
                        .findAll(specification, Sort.by(Sort.Direction.ASC, "timestamp", "eventId"))
                        .stream()
                        .map(AuditEventQueries::toDto)
                        .toList();
        boolean json = claim.format() == AuditExportFormat.JSON;
        String content = json ? objectMapper.writeValueAsString(events) : AuditExportCsv.render(events);
        String fileName = "audit-export-" + jobId + (json ? ".json" : ".csv");
        return new Rendered(fileName, json ? JSON_CONTENT_TYPE : CSV_CONTENT_TYPE, content, events.size());
    }

    private void complete(UUID jobId, Rendered rendered) {
        AuditExportJob job = jobRepository.findByJobId(jobId).orElse(null);
        if (job == null || job.getStatus() != AuditExportStatus.IN_PROGRESS) {
            // Marked FAILED as interrupted while it ran: keep the outcome callers already saw.
            return;
        }
        AuditExportFile file = new AuditExportFile();
        file.setJobId(jobId);
        file.setFileName(rendered.fileName());
        file.setContentType(rendered.contentType());
        file.setContent(rendered.content());
        file.setSizeBytes(rendered.content().getBytes(StandardCharsets.UTF_8).length);
        fileRepository.save(file);

        job.setStatus(AuditExportStatus.COMPLETED);
        job.setCompletedAt(clock.instant());
        job.setRowCount((long) rendered.rowCount());
        job.setErrorMessage(null);
        jobRepository.save(job);
    }

    private void markFailed(UUID jobId, String message) {
        try {
            writeTx.executeWithoutResult(status -> jobRepository
                    .findByJobId(jobId)
                    .filter(job -> !isTerminal(job.getStatus()))
                    .ifPresent(job -> fail(job, message)));
        } catch (RuntimeException e) {
            // Nothing else to do: a later read marks the job FAILED as interrupted.
            log.error("Audit export {} could not be marked FAILED", jobId, e);
        }
    }

    private void failIfStale(AuditExportJob job) {
        if (isTerminal(job.getStatus())) {
            return;
        }
        Instant deadline = job.getRequestedAt().plus(properties.staleAfter());
        if (deadline.isBefore(clock.instant())) {
            log.warn("Audit export {} left {} past {}; marking it FAILED", job.getJobId(), job.getStatus(), deadline);
            fail(job, INTERRUPTED_MESSAGE);
        }
    }

    private void fail(AuditExportJob job, String message) {
        job.setStatus(AuditExportStatus.FAILED);
        job.setCompletedAt(clock.instant());
        job.setErrorMessage(message.length() > ERROR_MESSAGE_MAX ? message.substring(0, ERROR_MESSAGE_MAX) : message);
        jobRepository.save(job);
    }

    private AuditExportJob findJob(UUID jobId) {
        return jobRepository
                .findByJobId(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Audit export job not found: " + jobId));
    }

    private static boolean isTerminal(AuditExportStatus status) {
        return status == AuditExportStatus.COMPLETED || status == AuditExportStatus.FAILED;
    }

    private static AuditExportJobResponse toResponse(AuditExportJob job) {
        boolean downloadable =
                job.getStatus() == AuditExportStatus.COMPLETED && job.getDeliveryMode() == AuditDeliveryMode.DOWNLOAD;
        return AuditExportJobResponse.builder()
                .jobId(job.getJobId())
                .status(job.getStatus())
                .format(job.getFormat())
                .requestedAt(job.getRequestedAt())
                .completedAt(job.getCompletedAt())
                .rowCount(job.getRowCount())
                .downloadUrl(downloadable ? DOWNLOAD_PATH_TEMPLATE.formatted(job.getJobId()) : null)
                .errorMessage(job.getErrorMessage())
                .build();
    }

    private static String blankToNull(String value) {
        return AuditEventQueries.isBlank(value) ? null : value;
    }

    private record Claim(AuditEventSearchFilter filter, AuditExportFormat format) {}

    private record Rendered(String fileName, String contentType, String content, int rowCount) {}

    /** The filters match more events than {@link AuditExportProperties#maxRows()} allows. */
    private static final class ExportTooLargeException extends RuntimeException {

        ExportTooLargeException(long matching, int limit) {
            super("The export matches " + matching + " audit events, more than the limit of " + limit
                    + "; narrow the filters (for example the date range) and request it again.");
        }
    }
}
