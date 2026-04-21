package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.dto.ColumnMappingApproveRequest;
import com.positivity.bulkloader.internal.dto.ColumnMappingResponse;
import com.positivity.bulkloader.internal.dto.ColumnMappingUpdateRequest;
import com.positivity.bulkloader.internal.entity.BulkLoadColumnMapping;
import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.repository.BulkLoadColumnMappingRepository;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.service.ColumnMappingService;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ColumnMappingServiceImpl implements ColumnMappingService {

    private final BulkLoadColumnMappingRepository mappingRepository;
    private final BulkLoadJobRepository jobRepository;

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<ColumnMappingResponse> getMappingsForJob(@NonNull UUID jobId, @NonNull String operatorId) {
        verifyOwnership(jobId, operatorId);
        return mappingRepository.findByJobId(jobId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    @NonNull
    public List<ColumnMappingResponse> approveMappings(
            @NonNull UUID jobId, @NonNull String operatorId, @NonNull ColumnMappingApproveRequest request) {
        verifyOwnership(jobId, operatorId);
        mappingRepository.deleteByJobId(jobId);
        List<BulkLoadColumnMapping> saved = new ArrayList<>();
        for (ColumnMappingUpdateRequest update : request.getMappings()) {
            BulkLoadColumnMapping mapping = new BulkLoadColumnMapping();
            mapping.setJobId(jobId);
            mapping.setSourceColumn(update.getSourceColumn());
            mapping.setTargetField(update.getTargetField());
            mapping.setConfidence(1.0);
            mapping.setOverriddenByUser(true);
            saved.add(mappingRepository.save(mapping));
        }
        log.info("Approved {} column mappings for job {}", saved.size(), jobId);
        return saved.stream().map(this::toResponse).toList();
    }

    private ColumnMappingResponse toResponse(@NonNull BulkLoadColumnMapping mapping) {
        return ColumnMappingResponse.builder()
                .id(mapping.getId())
                .jobId(mapping.getJobId())
                .sourceColumn(mapping.getSourceColumn())
                .targetField(mapping.getTargetField())
                .confidence(mapping.getConfidence())
                .overriddenByUser(mapping.getOverriddenByUser())
                .createdAt(mapping.getCreatedAt())
                .build();
    }

    private void verifyOwnership(@NonNull UUID jobId, @NonNull String operatorId) {
        var job = jobRepository
                .findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("BulkLoadJob not found: " + jobId));
        if (!job.getOperatorId().equals(operatorId)) {
            throw new JobOwnershipViolationException(jobId.toString());
        }
    }
}
