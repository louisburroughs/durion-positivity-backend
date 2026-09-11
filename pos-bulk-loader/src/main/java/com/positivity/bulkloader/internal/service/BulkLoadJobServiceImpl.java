package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantResolver;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Job lifecycle. Two operations rebind the tenant (ADR-0062, plan WS8): {@link #createJob} runs
 * under the job's target tenant so the row lands there, and {@link #startProcessing} runs under
 * the job's tenant so the whole batch, every audit row and every sibling call carries it. Both
 * open their transaction <em>inside</em> the binding through a {@link TransactionTemplate} rather
 * than {@code @Transactional}: the connection binds {@code app.current_tenant} at checkout and the
 * Hibernate session fixes its {@code @TenantId} when it opens, so a transaction begun before the
 * rebind would write as the caller's tenant (the pattern {@code docs/TENANCY_SCHEMA.md} prescribes
 * for per-tenant work). Every other operation reads and writes under the request's own binding,
 * which row-level security and the Hibernate filter scope like any other read.
 */
@Service
@Slf4j
public class BulkLoadJobServiceImpl implements BulkLoadJobService {

    private static final List<JobStatus> ACTIVE_STATUSES = List.of(
            JobStatus.CREATED,
            JobStatus.UPLOADING,
            JobStatus.DETECTING,
            JobStatus.MAPPING_REVIEW,
            JobStatus.DEDUP,
            JobStatus.PROCESSING);

    /**
     * States a job cannot leave by uploading or cancelling. Kept as one set so PARTIAL — added
     * when a run finishes with rejected rows — is treated as terminal everywhere at once, rather
     * than in three separate condition chains that could drift apart.
     */
    private static final Set<JobStatus> TERMINAL_STATUSES =
            EnumSet.of(JobStatus.COMPLETED, JobStatus.PARTIAL, JobStatus.FAILED, JobStatus.CANCELLED);

    /** Terminal states a run can be retried from: the work stopped short, so re-running it means something. */
    private static final Set<JobStatus> RETRYABLE_STATUSES = EnumSet.of(JobStatus.FAILED, JobStatus.PARTIAL);

    private final BulkLoadJobRepository jobRepository;
    private final BulkLoadBatchLauncher bulkLoadBatchLauncher;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;
    private final BulkLoadTenantBinding tenantBinding;
    private final TenantResolver tenantResolver;

    public BulkLoadJobServiceImpl(
            BulkLoadJobRepository jobRepository,
            BulkLoadBatchLauncher bulkLoadBatchLauncher,
            Clock clock,
            PlatformTransactionManager transactionManager,
            BulkLoadTenantBinding tenantBinding,
            TenantResolver tenantResolver) {
        this.jobRepository = jobRepository;
        this.bulkLoadBatchLauncher = bulkLoadBatchLauncher;
        this.clock = clock;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.tenantBinding = tenantBinding;
        this.tenantResolver = tenantResolver;
    }

    /**
     * Resolves the target tenant first (a 400 or 403 before anything is written), then creates the
     * job under that tenant's binding.
     */
    @Override
    public BulkLoadJobResponse createJob(@NonNull BulkLoadJobCreateRequest request, @NonNull String operatorId) {
        UUID tenantId = tenantBinding.resolveTarget(request.getTenantId());
        return TenantContext.callAs(
                tenantId,
                () -> transactionTemplate.execute(status -> createJobInTenant(request, operatorId, tenantId)));
    }

    private BulkLoadJobResponse createJobInTenant(BulkLoadJobCreateRequest request, String operatorId, UUID tenantId) {
        long activeCount = jobRepository.countByOperatorIdAndStatusIn(operatorId, ACTIVE_STATUSES);
        if (activeCount > 0) {
            throw new IllegalStateException("Operator already has an active bulk load job in progress");
        }

        BulkLoadJob job = new BulkLoadJob();
        job.setOperatorId(operatorId);
        job.setLocationId(request.getLocationId());
        job.setFileName(request.getFileName());
        job.setDomainType(request.getDomainType());
        job.setStatus(JobStatus.CREATED);

        BulkLoadJob saved;
        try {
            saved = jobRepository.save(job);
        } catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException("Operator already has an active bulk load job in progress", ex);
        }
        log.info(
                "Created bulk load job {} for operator {} domain {} in tenant {}",
                saved.getId(),
                operatorId,
                request.getDomainType(),
                tenantId);
        // Hibernate stamps tenant_id at flush, after this transaction's work, so the entity's own
        // tenant is still null here; the response names the tenant the row was created under.
        return toResponse(saved, tenantId);
    }

    @Override
    @Transactional
    public void markUploadStored(@NonNull UUID jobId, @NonNull String operatorId, @NonNull String storagePath) {
        // Defensive/internal invariant (issue #1694 audit), left as bare IllegalArgumentException:
        // both call sites (FileUploadController's multipart upload and TusUploadServiceImpl's
        // resumable-upload completion) pass a storagePath they just got back from
        // FileStorageService.store(...)/relativize(...) — a server-computed value, never raw
        // client input — so a blank value here means the storage layer is broken, not that the
        // caller sent something bad. Reachable synchronously from the HTTP thread if it ever
        // fires, which is correctly a 500, not a 400.
        if (storagePath.isBlank()) {
            throw new IllegalArgumentException("storagePath must not be blank");
        }

        BulkLoadJob job = findOrThrow(jobId);
        if (!job.getOperatorId().equals(operatorId)) {
            throw new JobOwnershipViolationException(jobId.toString());
        }
        if (TERMINAL_STATUSES.contains(job.getStatus())) {
            throw new IllegalStateException("Job cannot accept uploads in terminal state: " + job.getStatus());
        }

        job.setOriginalFilePath(storagePath);
        if (job.getStatus() == JobStatus.CREATED) {
            job.setStatus(JobStatus.UPLOADING);
        }
        jobRepository.save(job);
    }

    @Override
    @Transactional(readOnly = true)
    public BulkLoadJobResponse getJob(@NonNull UUID jobId, @NonNull String operatorId) {
        return toResponse(findByIdAndOperatorOrThrow(jobId, operatorId));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BulkLoadJobResponse> listJobsForOperator(@NonNull String operatorId, @NonNull Pageable pageable) {
        return jobRepository.findByOperatorId(operatorId, pageable).map(this::toResponse);
    }

    @Override
    @Transactional
    public BulkLoadJobResponse cancelJob(@NonNull UUID jobId, @NonNull String operatorId) {
        BulkLoadJob job = findOrThrow(jobId);
        if (!job.getOperatorId().equals(operatorId)) {
            throw new JobOwnershipViolationException(jobId.toString());
        }
        if (TERMINAL_STATUSES.contains(job.getStatus())) {
            throw new IllegalStateException("Job is already in terminal state: " + job.getStatus());
        }

        job.setStatus(JobStatus.CANCELLED);
        return toResponse(jobRepository.save(job));
    }

    @Override
    @Transactional
    public BulkLoadJobResponse retryJob(@NonNull UUID jobId, @NonNull String operatorId) {
        BulkLoadJob job = findOrThrow(jobId);
        if (!job.getOperatorId().equals(operatorId)) {
            throw new JobOwnershipViolationException(jobId.toString());
        }
        if (!RETRYABLE_STATUSES.contains(job.getStatus())) {
            throw new IllegalStateException(
                    "Job can only be retried from FAILED or PARTIAL state, current state: " + job.getStatus());
        }

        long activeCount = jobRepository.countByOperatorIdAndStatusIn(operatorId, ACTIVE_STATUSES);
        if (activeCount > 0) {
            throw new IllegalStateException("Operator already has an active bulk load job in progress");
        }

        job.setStatus(JobStatus.CREATED);
        job.setStartedAt(null);
        job.setCompletedAt(null);
        job.setProcessedRows(0L);
        job.setSuccessCount(0L);
        job.setFailureCount(0L);
        job.setTotalRows(null);
        return toResponse(jobRepository.save(job));
    }

    /**
     * Runs the whole job under its tenant. The job is visible only under the binding that owns it
     * (row-level security), so the tenant to run as is the one resolved for this request; it is
     * re-bound explicitly, around the transaction and the batch launch, so the run does not depend
     * on the request thread's binding surviving into the batch (it would not on an async executor).
     */
    @Override
    public void startProcessing(@NonNull UUID jobId, @NonNull String operatorId, @Nullable String authorizationHeader) {
        UUID tenantId = tenantResolver.require();
        TenantContext.runAs(
                tenantId,
                () -> transactionTemplate.executeWithoutResult(
                        status -> startProcessingInTenant(jobId, operatorId, authorizationHeader, tenantId)));
    }

    private void startProcessingInTenant(
            UUID jobId, String operatorId, @Nullable String authorizationHeader, UUID tenantId) {
        BulkLoadJob job = findOrThrow(jobId);
        if (!job.getOperatorId().equals(operatorId)) {
            throw new JobOwnershipViolationException(jobId.toString());
        }
        if (job.getStatus() != JobStatus.CREATED
                && job.getStatus() != JobStatus.UPLOADING
                && job.getStatus() != JobStatus.MAPPING_REVIEW) {
            throw new IllegalStateException("Job cannot be transitioned to PROCESSING from state: " + job.getStatus());
        }
        if (job.getOriginalFilePath() == null || job.getOriginalFilePath().isBlank()) {
            throw new IllegalStateException("Job cannot be processed before an uploaded file is persisted");
        }
        if (job.getLocationId() == null) {
            throw new IllegalStateException("Job cannot be processed before a locationId is assigned");
        }
        // PROCESSING is stamped *before* the launch, not after (issue #1712). The module
        // configures no TaskExecutor or JobLauncher bean, so Spring Boot Batch's default
        // JobOperator runs the job on a SyncTaskExecutor — the whole import finishes, and
        // BulkLoadJobExecutionListener#afterJob writes the terminal status, before launch()
        // returns. Writing PROCESSING afterwards clobbered that terminal status on the very same
        // managed entity instance, so every finished job read as PROCESSING for ever while its
        // row counts said otherwise. In this order the listener has the last word.
        //
        // What this does NOT do is make PROCESSING observable to anyone else. This method runs
        // inside the TransactionTemplate startProcessing opened under the job's tenant, and save()
        // on an already-managed entity is a merge, not a flush: the UPDATE lands at commit, by
        // which time the listener has already replaced the status. A second connection polling the
        // job during the import still reads CREATED or UPLOADING. That is a consequence of running
        // the import inside the request transaction, and it goes away with the same change that
        // makes processing genuinely asynchronous — at which point the launch must also move after
        // this transaction commits (still inside the tenant binding, which the task decorator then
        // carries to the batch thread), or the batch thread will race a row it cannot yet see, and
        // contend with the lock this one would then hold for the length of the import. See the
        // note on FileUploadController#startProcessing.
        job.setStatus(JobStatus.PROCESSING);
        job.setStartedAt(Instant.now(clock));
        jobRepository.save(job);
        log.info("Starting bulk load job {} domain {} in tenant {}", job.getId(), job.getDomainType(), tenantId);
        bulkLoadBatchLauncher.launch(job, authorizationHeader);
    }

    private BulkLoadJob findOrThrow(UUID jobId) {
        return jobRepository
                .findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("BulkLoadJob not found: " + jobId));
    }

    private BulkLoadJob findByIdAndOperatorOrThrow(UUID jobId, String operatorId) {
        return jobRepository
                .findByIdAndOperatorId(jobId, operatorId)
                .orElseThrow(() -> new NoSuchElementException("BulkLoadJob not found: " + jobId));
    }

    private BulkLoadJobResponse toResponse(BulkLoadJob job) {
        return toResponse(job, job.getTenantId());
    }

    private BulkLoadJobResponse toResponse(BulkLoadJob job, @Nullable UUID tenantId) {
        return BulkLoadJobResponse.builder()
                .id(job.getId())
                .operatorId(job.getOperatorId())
                .locationId(job.getLocationId())
                .tenantId(tenantId)
                .fileName(job.getFileName())
                .domainType(job.getDomainType())
                .status(job.getStatus())
                .totalRows(job.getTotalRows())
                .processedRows(job.getProcessedRows())
                .successCount(job.getSuccessCount())
                .failureCount(job.getFailureCount())
                .startedAt(job.getStartedAt())
                .completedAt(job.getCompletedAt())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}
