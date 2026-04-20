package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.dto.BulkLoadJobCreateRequest;
import com.positivity.bulkloader.internal.dto.BulkLoadJobResponse;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.JobStatus;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.service.BulkLoadJobService;
import java.time.Instant;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class BulkLoadJobServiceImpl implements BulkLoadJobService {

    private static final List<JobStatus> ACTIVE_STATUSES = List.of(
            JobStatus.UPLOADING,
            JobStatus.DETECTING,
            JobStatus.MAPPING_REVIEW,
            JobStatus.DEDUP,
            JobStatus.PROCESSING);

    private final BulkLoadJobRepository jobRepository;

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

        BulkLoadJob saved = jobRepository.save(job);
        log.info("Created bulk load job {} for operator {} domain {}", saved.getId(), operatorId, request.getDomainType());
        return toResponse(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public BulkLoadJobResponse getJob(@NonNull UUID jobId) {
        return toResponse(findOrThrow(jobId));
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
            throw new IllegalArgumentException("Job does not belong to operator");
        }
        if (job.getStatus() == JobStatus.COMPLETED || job.getStatus() == JobStatus.CANCELLED) {
            throw new IllegalStateException("Job is already in terminal state: " + job.getStatus());
        }

        job.setStatus(JobStatus.CANCELLED);
        return toResponse(jobRepository.save(job));
    }

    @Override
    @Transactional
    public void startProcessing(@NonNull UUID jobId) {
        BulkLoadJob job = findOrThrow(jobId);
        job.setStatus(JobStatus.PROCESSING);
        job.setStartedAt(Instant.now());
        jobRepository.save(job);
    }

    private BulkLoadJob findOrThrow(UUID jobId) {
        return jobRepository
                .findById(jobId)
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
