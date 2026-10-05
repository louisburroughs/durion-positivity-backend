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
import java.util.Set;
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
 * requesting tenant to the worker. The worker claims the job (IN_PROGRESS), pages through the
 * matching audit events by event id over the same filter path as {@code searchAuditEvents} (each
 * page in its own read-only transaction, so no entity outlives its page), renders CSV or JSON
 * incrementally under both a row and a byte cap, and stores the file with the job (COMPLETED) — or
 * records a sanitised reason (FAILED).
 *
 * <p>Every lifecycle transition is a status-conditional update: completion writes the file only if
 * the job is still IN_PROGRESS in the same transaction, so a job the sweep has already failed never
 * gains an orphan file. Reads never change state; {@link #sweepBoundTenant()}, run per tenant on a
 * schedule, fails jobs past {@link AuditExportProperties#staleAfter()} and purges jobs past {@link
 * AuditExportProperties#retention()}.
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
    static final String TOO_MANY_ROWS_MESSAGE = "The export matches more than %d audit events, the configured limit;"
            + " narrow the filters (for example the date range) and request it again.";
    static final String TOO_MANY_BYTES_MESSAGE = "The export file would be larger than %d bytes, the configured limit;"
            + " narrow the filters (for example the date range) and request it again.";
    static final int PAGE_SIZE = 500;
    private static final Set<AuditExportStatus> ACTIVE =
            Set.of(AuditExportStatus.PENDING, AuditExportStatus.IN_PROGRESS);
    private static final Sort BY_EVENT_ID = Sort.by(Sort.Direction.ASC, "eventId");

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
    @Transactional(readOnly = true)
    public AuditExportJobResponse getExportJob(@NonNull UUID jobId) {
        return toResponse(findJob(jobId));
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
            Rendered rendered = render(jobId, claim);
            Boolean completed = writeTx.execute(status -> complete(jobId, rendered));
            if (!Boolean.TRUE.equals(completed)) {
                log.info("Audit export {} finished after it had been failed; result discarded", jobId);
            }
        } catch (ExportLimitException e) {
            log.warn("Audit export {} refused: {}", jobId, e.getMessage());
            markFailed(jobId, e.getMessage());
        } catch (RuntimeException e) {
            log.error("Audit export {} failed", jobId, e);
            markFailed(jobId, GENERIC_FAILURE_MESSAGE);
        }
    }

    @Override
    @NonNull
    public SweepResult sweepBoundTenant() {
        SweepResult result = writeTx.execute(status -> {
            Instant now = clock.instant();
            int interrupted = jobRepository.failRequestedBefore(
                    ACTIVE, now.minus(properties.staleAfter()), INTERRUPTED_MESSAGE, now);
            Instant retentionCutoff = now.minus(properties.retention());
            fileRepository.deleteForExpiredJobs(retentionCutoff);
            int purged = jobRepository.deleteExpired(retentionCutoff);
            return new SweepResult(interrupted, purged);
        });
        return result == null ? new SweepResult(0, 0) : result;
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
        if (jobRepository.markInProgress(jobId, clock.instant()) == 0) {
            return null;
        }
        AuditExportJob job = jobRepository.findByJobId(jobId).orElse(null);
        if (job == null) {
            return null;
        }
        AuditEventSearchFilter filter = AuditEventSearchFilter.builder()
                .fromDate(job.getFilterFromDate())
                .toDate(job.getFilterToDate())
                .eventType(job.getFilterEventType())
                .actorId(job.getFilterActorId())
                .aggregateId(job.getFilterAggregateId())
                .build();
        return new Claim(filter, job.getFormat());
    }

    /**
     * Pages through the matching events by event id, reading at most {@code maxRows + 1} so an
     * over-limit export is detected without a separate count (which could disagree with the fetch
     * when events arrive mid-export), and stops as soon as the rendered file passes {@code maxBytes}.
     */
    private Rendered render(UUID jobId, Claim claim) {
        boolean json = claim.format() == AuditExportFormat.JSON;
        Specification<AuditLogEvent> filter = AuditEventQueries.specification(claim.filter());
        StringBuilder out = new StringBuilder();
        long[] bytes = {0};
        append(out, bytes, json ? "[" : AuditExportCsv.headerLine());
        int rows = 0;
        UUID lastEventId = null;
        while (true) {
            int limit = (int) Math.min(PAGE_SIZE, (long) properties.maxRows() + 1 - rows);
            UUID after = lastEventId;
            List<AuditLogEventDto> page = readTx.execute(status -> fetchPage(filter, after, limit));
            if (page == null || page.isEmpty()) {
                break;
            }
            for (AuditLogEventDto event : page) {
                rows++;
                if (rows > properties.maxRows()) {
                    throw new ExportLimitException(TOO_MANY_ROWS_MESSAGE.formatted(properties.maxRows()));
                }
                String piece = json
                        ? (rows > 1 ? "," : "") + objectMapper.writeValueAsString(event)
                        : AuditExportCsv.row(event);
                append(out, bytes, piece);
                lastEventId = event.getEventId();
            }
            if (page.size() < limit) {
                break;
            }
        }
        if (json) {
            append(out, bytes, "]");
        }
        String fileName = "audit-export-" + jobId + (json ? ".json" : ".csv");
        return new Rendered(fileName, json ? JSON_CONTENT_TYPE : CSV_CONTENT_TYPE, out.toString(), bytes[0], rows);
    }

    private List<AuditLogEventDto> fetchPage(Specification<AuditLogEvent> filter, UUID after, int limit) {
        return auditLogEventRepository
                .findBy(
                        filter.and(AuditEventQueries.after(after)),
                        query -> query.sortBy(BY_EVENT_ID).limit(limit).all())
                .stream()
                .map(AuditEventQueries::toDto)
                .toList();
    }

    private void append(StringBuilder out, long[] bytes, String piece) {
        bytes[0] += utf8Length(piece);
        if (bytes[0] > properties.maxBytes()) {
            throw new ExportLimitException(TOO_MANY_BYTES_MESSAGE.formatted(properties.maxBytes()));
        }
        out.append(piece);
    }

    /** Encoded UTF-8 length without materialising the bytes. */
    static long utf8Length(CharSequence text) {
        long length = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                length += 1;
            } else if (c < 0x800) {
                length += 2;
            } else if (Character.isHighSurrogate(c)
                    && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                length += 4;
                i++;
            } else {
                length += 3;
            }
        }
        return length;
    }

    /**
     * Marks the job COMPLETED and stores its file in one transaction, only if the job is still
     * IN_PROGRESS: when the sweep has failed it meanwhile, nothing is written.
     */
    private boolean complete(UUID jobId, Rendered rendered) {
        if (jobRepository.markCompleted(jobId, rendered.rowCount(), clock.instant()) == 0) {
            return false;
        }
        AuditExportFile file = new AuditExportFile();
        file.setJobId(jobId);
        file.setFileName(rendered.fileName());
        file.setContentType(rendered.contentType());
        file.setContent(rendered.content());
        file.setSizeBytes(rendered.sizeBytes());
        fileRepository.save(file);
        return true;
    }

    private void markFailed(UUID jobId, String message) {
        try {
            writeTx.executeWithoutResult(status -> jobRepository.markFailed(jobId, ACTIVE, message, clock.instant()));
        } catch (RuntimeException e) {
            // Nothing else to do: the scheduled sweep fails the job as interrupted.
            log.error("Audit export {} could not be marked FAILED", jobId, e);
        }
    }

    private AuditExportJob findJob(UUID jobId) {
        return jobRepository
                .findByJobId(jobId)
                .orElseThrow(() -> new EntityNotFoundException("Audit export job not found: " + jobId));
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

    private record Rendered(String fileName, String contentType, String content, long sizeBytes, int rowCount) {}

    /** The export passed {@link AuditExportProperties#maxRows()} or {@link AuditExportProperties#maxBytes()}. */
    private static final class ExportLimitException extends RuntimeException {

        ExportLimitException(String message) {
            super(message);
        }
    }
}
