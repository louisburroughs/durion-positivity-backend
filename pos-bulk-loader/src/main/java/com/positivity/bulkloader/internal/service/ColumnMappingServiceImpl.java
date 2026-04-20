package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.dto.ColumnMappingApproveRequest;
import com.positivity.bulkloader.internal.dto.ColumnMappingResponse;
import com.positivity.bulkloader.internal.dto.ColumnMappingUpdateRequest;
import com.positivity.bulkloader.internal.entity.BulkLoadColumnMapping;
import com.positivity.bulkloader.internal.repository.BulkLoadColumnMappingRepository;
import com.positivity.bulkloader.service.ColumnMappingService;
import java.util.ArrayList;
import java.util.List;
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

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public List<ColumnMappingResponse> getMappingsForJob(@NonNull UUID jobId) {
        return mappingRepository.findByJobId(jobId).stream()
                .map(this::toResponse)
                .toList();
    }

    @Override
    @Transactional
    @NonNull
    public List<ColumnMappingResponse> approveMappings(
            @NonNull UUID jobId, @NonNull ColumnMappingApproveRequest request) {
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
}
