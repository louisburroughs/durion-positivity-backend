package com.positivity.securityservice.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.securityservice.internal.dto.PricingRuleTraceEntryDto;
import com.positivity.securityservice.internal.dto.PricingRuleTraceEntryRequest;
import com.positivity.securityservice.internal.dto.PricingSnapshotDto;
import com.positivity.securityservice.internal.dto.PricingSnapshotRequest;
import com.positivity.securityservice.internal.entity.PricingRuleTraceEntry;
import com.positivity.securityservice.internal.entity.PricingSnapshot;
import com.positivity.securityservice.internal.repository.PricingRuleTraceEntryRepository;
import com.positivity.securityservice.internal.repository.PricingSnapshotRepository;
import com.positivity.securityservice.service.PricingSnapshotService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service implementation for immutable pricing snapshot persistence and
 * retrieval.
 *
 * Issue: #41
 */
@Service
@RequiredArgsConstructor
public class PricingSnapshotServiceImpl implements PricingSnapshotService {

    private final PricingSnapshotRepository pricingSnapshotRepository;
    private final PricingRuleTraceEntryRepository pricingRuleTraceEntryRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public PricingSnapshotDto createSnapshot(@NonNull PricingSnapshotRequest request) {
        validateRequest(request);

        PricingSnapshot snapshot = new PricingSnapshot();
        snapshot.setQuoteContext(writeJson(request.getQuoteContext()));
        snapshot.setFinalPrice(request.getFinalPrice());
        PricingSnapshot savedSnapshot = pricingSnapshotRepository.save(snapshot);

        // Issue #41: Build immutable evaluation step response in the same transaction.
        List<PricingRuleTraceEntryDto> createdSteps = new java.util.ArrayList<>();

        for (PricingRuleTraceEntryRequest step : request.getEvaluationSteps()) {
            PricingRuleTraceEntry entry = new PricingRuleTraceEntry();
            entry.setSnapshot(savedSnapshot);
            entry.setRuleId(step.getRuleId());
            entry.setStatus(step.getStatus());
            entry.setInputs(writeJson(step.getInputs()));
            entry.setOutputs(writeJson(step.getOutputs()));
            PricingRuleTraceEntry savedEntry = pricingRuleTraceEntryRepository.save(entry);
            createdSteps.add(toDto(savedEntry));
        }

        return PricingSnapshotDto.builder()
                .snapshotId(savedSnapshot.getSnapshotId())
                .timestamp(savedSnapshot.getTimestamp())
                .quoteContext(savedSnapshot.getQuoteContext())
                .finalPrice(savedSnapshot.getFinalPrice())
                .evaluationSteps(createdSteps)
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PricingSnapshotDto getSnapshot(@NonNull UUID snapshotId) {
        PricingSnapshot snapshot = pricingSnapshotRepository
                .findById(snapshotId)
                .orElseThrow(() -> new IllegalArgumentException("Pricing snapshot not found: " + snapshotId));

        List<PricingRuleTraceEntryDto> steps =
                pricingRuleTraceEntryRepository.findBySnapshot_SnapshotIdOrderByRuleIdAsc(snapshotId).stream()
                        .map(this::toDto)
                        .toList();

        return PricingSnapshotDto.builder()
                .snapshotId(snapshot.getSnapshotId())
                .timestamp(snapshot.getTimestamp())
                .quoteContext(snapshot.getQuoteContext())
                .finalPrice(snapshot.getFinalPrice())
                .evaluationSteps(steps)
                .build();
    }

    private void validateRequest(@NonNull PricingSnapshotRequest request) {
        if (request.getQuoteContext() == null) {
            throw new IllegalArgumentException("quoteContext is required");
        }
        if (request.getFinalPrice() == null) {
            throw new IllegalArgumentException("finalPrice is required");
        }
        if (request.getEvaluationSteps() == null) {
            throw new IllegalArgumentException("evaluationSteps is required");
        }
        for (PricingRuleTraceEntryRequest step : request.getEvaluationSteps()) {
            if (step == null || step.getRuleId() == null || step.getRuleId().isBlank()) {
                throw new IllegalArgumentException("evaluation step ruleId is required");
            }
            if (step.getStatus() == null || step.getStatus().isBlank()) {
                throw new IllegalArgumentException("evaluation step status is required");
            }
            if (step.getInputs() == null) {
                throw new IllegalArgumentException("evaluation step inputs are required");
            }
            if (step.getOutputs() == null) {
                throw new IllegalArgumentException("evaluation step outputs are required");
            }
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize JSON field", exception);
        }
    }

    private PricingRuleTraceEntryDto toDto(PricingRuleTraceEntry entry) {
        return PricingRuleTraceEntryDto.builder()
                .id(entry.getId().toString())
                .ruleId(entry.getRuleId())
                .status(entry.getStatus())
                .inputs(entry.getInputs())
                .outputs(entry.getOutputs())
                .build();
    }
}
