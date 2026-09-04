package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
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

    @Override
    @Transactional
    public BulkLoadJobResponse createJob(@NonNull BulkLoadJobCreateRequest request, @NonNull String operatorId) {
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
                "Created bulk load job {} for operator {} domain {}",
                saved.getId(),
                operatorId,
                request.getDomainType());
        return toResponse(saved);
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

    @Override
    @Transactional
    public void startProcessing(@NonNull UUID jobId, @NonNull String operatorId, @Nullable String authorizationHeader) {
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
        bulkLoadBatchLauncher.launch(job, authorizationHeader);
        job.setStatus(JobStatus.PROCESSING);
        job.setStartedAt(Instant.now(clock));
        jobRepository.save(job);
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
        return BulkLoadJobResponse.builder()
                .id(job.getId())
                .operatorId(job.getOperatorId())
                .locationId(job.getLocationId())
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
