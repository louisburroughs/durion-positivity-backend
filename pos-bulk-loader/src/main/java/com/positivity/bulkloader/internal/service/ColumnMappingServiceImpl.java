package com.positivity.bulkloader.internal.service;

import com.positivity.bulkloader.internal.dto.ColumnMappingApproveRequest;
import com.positivity.bulkloader.internal.dto.ColumnMappingResponse;
import com.positivity.bulkloader.internal.dto.ColumnMappingUpdateRequest;
import com.positivity.bulkloader.internal.dto.ContentDetectionResult;
import com.positivity.bulkloader.internal.entity.BulkLoadColumnMapping;
import com.positivity.bulkloader.internal.entity.BulkLoadJob;
import com.positivity.bulkloader.internal.enums.DomainType;
import com.positivity.bulkloader.internal.exception.JobOwnershipViolationException;
import com.positivity.bulkloader.internal.parser.RecordFileParserRegistry;
import com.positivity.bulkloader.internal.parser.RecordSource;
import com.positivity.bulkloader.internal.repository.BulkLoadColumnMappingRepository;
import com.positivity.bulkloader.internal.repository.BulkLoadJobRepository;
import com.positivity.bulkloader.service.ColumnMappingService;
import com.positivity.bulkloader.service.ContentDetectionService;
import com.positivity.bulkloader.service.FileStorageService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@RequiredArgsConstructor
@Slf4j
public class ColumnMappingServiceImpl implements ColumnMappingService {

    private static final int SAMPLE_ROW_LIMIT = 20;

    private final BulkLoadColumnMappingRepository mappingRepository;
    private final BulkLoadJobRepository jobRepository;
    private final ContentDetectionService contentDetectionService;
    private final FileStorageService fileStorageService;
    private final RecordFileParserRegistry recordFileParserRegistry;
    private final TransactionTemplate transactionTemplate;

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

    @Override
    @Transactional(readOnly = true)
    @NonNull
    public Map<String, String> resolveEffectiveMappings(
            @Nullable UUID jobId, @NonNull List<String> columnHeaders, @NonNull DomainType domainType) {
        Map<String, String> stored = new LinkedHashMap<>();
        if (jobId != null) {
            for (BulkLoadColumnMapping mapping : mappingRepository.findByJobId(jobId)) {
                stored.putIfAbsent(mapping.getSourceColumn(), mapping.getTargetField());
            }
        }
        Map<String, String> suggested = contentDetectionService.suggestMappings(columnHeaders, domainType);

        Map<String, String> effective = new LinkedHashMap<>();
        Set<String> claimedTargets = new HashSet<>();
        for (String header : columnHeaders) {
            String target = stored.get(header);
            if (target != null && claimedTargets.add(target)) {
                effective.put(header, target);
            }
        }
        for (String header : columnHeaders) {
            if (effective.containsKey(header)) {
                continue;
            }
            String target = suggested.get(header);
            if (target != null && claimedTargets.add(target)) {
                effective.put(header, target);
            }
        }
        return effective;
    }

    // Deliberately not @Transactional: file parsing can be slow and must not hold a DB
    // connection. Only the suggestion persistence below runs in a transaction.
    @Override
    @Nullable
    public ContentDetectionResult detectUploadedFile(
            @NonNull UUID jobId, @NonNull String operatorId, @NonNull String storagePath, @NonNull String fileName) {
        BulkLoadJob job = verifyOwnership(jobId, operatorId);

        List<String> headers;
        List<List<String>> sampleRows = new ArrayList<>();
        try (RecordSource source = recordFileParserRegistry.open(fileStorageService.retrieve(storagePath), fileName)) {
            headers = source.headers();
            Map<String, String> row;
            while (sampleRows.size() < SAMPLE_ROW_LIMIT && (row = source.nextRecord()) != null) {
                Map<String, String> currentRow = row;
                sampleRows.add(headers.stream()
                        .map(header -> currentRow.getOrDefault(header, ""))
                        .toList());
            }
        } catch (RuntimeException e) {
            log.warn("Content detection skipped for job {} file {}", jobId, fileName, e);
            return null;
        }

        ContentDetectionResult result = contentDetectionService.detect(headers, sampleRows);
        DomainType mappingDomain = job.getDomainType() != null ? job.getDomainType() : result.getDetectedDomain();
        if (job.getDomainType() != null && result.getDetectedDomain() != job.getDomainType()) {
            log.warn(
                    "Detected domain {} differs from job domain {} for job {}; suggesting mappings for the job domain",
                    result.getDetectedDomain(),
                    job.getDomainType(),
                    jobId);
        }
        Map<String, String> suggestions = contentDetectionService.suggestMappings(headers, mappingDomain);
        result.setSuggestedColumnMappings(suggestions);

        transactionTemplate.executeWithoutResult(_ -> persistSuggestions(jobId, result, suggestions));
        return result;
    }

    private void persistSuggestions(
            @NonNull UUID jobId, @NonNull ContentDetectionResult result, @NonNull Map<String, String> suggestions) {
        boolean hasUserApprovedMappings = mappingRepository.findByJobId(jobId).stream()
                .anyMatch(mapping -> Boolean.TRUE.equals(mapping.getOverriddenByUser()));
        if (hasUserApprovedMappings) {
            log.info("Keeping user-approved column mappings for job {}; suggestions not persisted", jobId);
            return;
        }

        mappingRepository.deleteByJobId(jobId);
        for (Map.Entry<String, String> suggestion : suggestions.entrySet()) {
            BulkLoadColumnMapping mapping = new BulkLoadColumnMapping();
            mapping.setJobId(jobId);
            mapping.setSourceColumn(suggestion.getKey());
            mapping.setTargetField(suggestion.getValue());
            mapping.setConfidence(result.getConfidence());
            mapping.setOverriddenByUser(false);
            mappingRepository.save(mapping);
        }
        log.info(
                "Detected {} with confidence {} for job {}; persisted {} suggested column mappings",
                result.getDetectedDomain(),
                result.getConfidence(),
                jobId,
                suggestions.size());
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

    private BulkLoadJob verifyOwnership(@NonNull UUID jobId, @NonNull String operatorId) {
        BulkLoadJob job = jobRepository
                .findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("BulkLoadJob not found: " + jobId));
        if (!job.getOperatorId().equals(operatorId)) {
            throw new JobOwnershipViolationException(jobId.toString());
        }
        return job;
    }
}
