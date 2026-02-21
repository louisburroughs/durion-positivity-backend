package com.positivity.securityservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.securityservice.internal.dto.PricingRuleTraceEntryRequest;
import com.positivity.securityservice.internal.dto.PricingSnapshotRequest;
import com.positivity.securityservice.internal.entity.PricingRuleTraceEntry;
import com.positivity.securityservice.internal.entity.PricingSnapshot;
import com.positivity.securityservice.internal.repository.PricingRuleTraceEntryRepository;
import com.positivity.securityservice.internal.repository.PricingSnapshotRepository;
import com.positivity.securityservice.internal.service.PricingSnapshotServiceImpl;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PricingSnapshotServiceImplTest {

    @Mock
    private PricingSnapshotRepository pricingSnapshotRepository;

    @Mock
    private PricingRuleTraceEntryRepository pricingRuleTraceEntryRepository;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PricingSnapshotServiceImpl pricingSnapshotService;

    @Test
    @DisplayName("createSnapshot stores snapshot with immutable rule trace entries")
    void createSnapshot_success_returnsDtoWithSteps() throws JsonProcessingException {
        PricingSnapshotRequest request = new PricingSnapshotRequest(
                Map.of("sku", "SKU-1"),
                new BigDecimal("129.99"),
                List.of(new PricingRuleTraceEntryRequest(
                        "RULE-1",
                        "APPLIED",
                        Map.of("basePrice", 149.99),
                        Map.of("discount", 20.00))));

        UUID snapshotId = UUID.randomUUID();
        Instant snapshotTimestamp = Instant.parse("2026-02-21T15:00:00Z");
        UUID stepId = UUID.randomUUID();

        when(objectMapper.writeValueAsString(request.getQuoteContext())).thenReturn("{\"sku\":\"SKU-1\"}");
        when(objectMapper.writeValueAsString(request.getEvaluationSteps().get(0).getInputs()))
                .thenReturn("{\"basePrice\":149.99}");
        when(objectMapper.writeValueAsString(request.getEvaluationSteps().get(0).getOutputs()))
                .thenReturn("{\"discount\":20.0}");

        when(pricingSnapshotRepository.save(any(PricingSnapshot.class))).thenAnswer(invocation -> {
            PricingSnapshot snapshot = invocation.getArgument(0);
            snapshot.setSnapshotId(snapshotId);
            snapshot.setTimestamp(snapshotTimestamp);
            return snapshot;
        });

        when(pricingRuleTraceEntryRepository.save(any(PricingRuleTraceEntry.class))).thenAnswer(invocation -> {
            PricingRuleTraceEntry entry = invocation.getArgument(0);
            entry.setId(stepId);
            return entry;
        });

        var result = pricingSnapshotService.createSnapshot(request);

        assertThat(result.getSnapshotId()).isEqualTo(snapshotId);
        assertThat(result.getTimestamp()).isEqualTo(snapshotTimestamp);
        assertThat(result.getQuoteContext()).isEqualTo("{\"sku\":\"SKU-1\"}");
        assertThat(result.getFinalPrice()).isEqualByComparingTo("129.99");
        assertThat(result.getEvaluationSteps()).hasSize(1);
        assertThat(result.getEvaluationSteps().get(0).getId()).isEqualTo(stepId.toString());
        assertThat(result.getEvaluationSteps().get(0).getRuleId()).isEqualTo("RULE-1");
        assertThat(result.getEvaluationSteps().get(0).getStatus()).isEqualTo("APPLIED");
    }

    @Test
    @DisplayName("createSnapshot throws when required fields are missing")
    void createSnapshot_missingQuoteContext_throws() {
        PricingSnapshotRequest request = new PricingSnapshotRequest(
                null,
                new BigDecimal("129.99"),
                List.of(new PricingRuleTraceEntryRequest("RULE-1", "APPLIED", Map.of(), Map.of())));

        assertThatThrownBy(() -> pricingSnapshotService.createSnapshot(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("quoteContext is required");
    }

    @Test
    @DisplayName("createSnapshot throws when evaluation step fields are invalid")
    void createSnapshot_invalidEvaluationStep_throws() {
        PricingSnapshotRequest request = new PricingSnapshotRequest(
                Map.of("sku", "SKU-2"),
                new BigDecimal("89.99"),
                List.of(new PricingRuleTraceEntryRequest("", "", null, null)));

        assertThatThrownBy(() -> pricingSnapshotService.createSnapshot(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("evaluation step ruleId is required");
    }

    @Test
    @DisplayName("createSnapshot throws when json serialization fails")
    void createSnapshot_jsonSerializationFailure_throws() throws JsonProcessingException {
        PricingSnapshotRequest request = new PricingSnapshotRequest(
                Map.of("sku", "SKU-3"),
                new BigDecimal("59.99"),
                List.of(new PricingRuleTraceEntryRequest("RULE-2", "APPLIED", Map.of("x", 1), Map.of("y", 2))));

        when(objectMapper.writeValueAsString(request.getQuoteContext())).thenThrow(new JsonProcessingException("bad json") {
        });

        assertThatThrownBy(() -> pricingSnapshotService.createSnapshot(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to serialize JSON field");
    }

    @Test
    @DisplayName("getSnapshot returns snapshot with ordered evaluation steps")
    void getSnapshot_found_returnsDto() {
        UUID snapshotId = UUID.randomUUID();

        PricingSnapshot snapshot = new PricingSnapshot();
        snapshot.setSnapshotId(snapshotId);
        snapshot.setTimestamp(Instant.parse("2026-02-21T16:00:00Z"));
        snapshot.setQuoteContext("{\"sku\":\"SKU-4\"}");
        snapshot.setFinalPrice(new BigDecimal("199.00"));

        PricingRuleTraceEntry step = new PricingRuleTraceEntry();
        step.setId(UUID.randomUUID());
        step.setRuleId("RULE-9");
        step.setStatus("SKIPPED");
        step.setInputs("{\"base\":199.0}");
        step.setOutputs("{\"discount\":0.0}");

        when(pricingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.of(snapshot));
        when(pricingRuleTraceEntryRepository.findBySnapshot_SnapshotIdOrderByRuleIdAsc(snapshotId))
                .thenReturn(List.of(step));

        var result = pricingSnapshotService.getSnapshot(snapshotId);

        assertThat(result.getSnapshotId()).isEqualTo(snapshotId);
        assertThat(result.getEvaluationSteps()).hasSize(1);
        assertThat(result.getEvaluationSteps().get(0).getRuleId()).isEqualTo("RULE-9");
    }

    @Test
    @DisplayName("getSnapshot throws when snapshot does not exist")
    void getSnapshot_notFound_throws() {
        UUID snapshotId = UUID.randomUUID();
        when(pricingSnapshotRepository.findById(snapshotId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> pricingSnapshotService.getSnapshot(snapshotId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Pricing snapshot not found");
    }
}
