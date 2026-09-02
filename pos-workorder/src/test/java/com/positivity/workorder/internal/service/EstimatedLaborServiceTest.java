package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.entity.ExtCatalogServiceReplica;
import com.positivity.workorder.internal.entity.WorkorderServiceLine;
import com.positivity.workorder.internal.enums.WorkorderItemStatus;
import com.positivity.workorder.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderServiceRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * The overlap-aware estimated-hours sum (#1569, sourcing plan §6.3 item 3) — pinned against the
 * naive sum, because "not a naive sum" is the whole requirement: shared setup must not be
 * double-billed and included operations must not be billed twice.
 */
@DisplayName("EstimatedLaborService")
class EstimatedLaborServiceTest {

    private static final UUID PADS_SERVICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID ROTORS_SERVICE = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private final WorkorderServiceRepository serviceRepository = mock(WorkorderServiceRepository.class);
    private final ExtCatalogServiceReplicaRepository replicaRepository = mock(ExtCatalogServiceReplicaRepository.class);

    private EstimatedLaborService service;

    @BeforeEach
    void setUp() {
        service = new EstimatedLaborService(serviceRepository, replicaRepository);
        ReflectionTestUtils.setField(service, "overlapAdditionalFactor", new BigDecimal("0.5"));
        when(replicaRepository.findByServiceIdIn(any())).thenReturn(List.of());
    }

    private static WorkorderServiceLine line(String hours, String overlapGroup, String includedCsv, UUID serviceId) {
        return WorkorderServiceLine.builder()
                .quantity(new BigDecimal(hours))
                .guideOverlapGroup(overlapGroup)
                .guideIncludedOpCodes(includedCsv)
                .serviceEntityId(serviceId)
                .declined(false)
                .status(WorkorderItemStatus.OPEN)
                .build();
    }

    @Test
    @DisplayName("independent lines just add up")
    void independentLinesSum() {
        var result = service.estimateForLines(List.of(line("1.5", null, null, null), line("1.0", null, null, null)));

        assertThat(result.estimatedHours()).isEqualByComparingTo("2.5");
    }

    @Test
    @DisplayName("an overlap group contributes max plus half of the rest — not the naive sum")
    void overlapGroupIsNotNaive() {
        // Front pads 1.5 + rear pads 1.2 share WHEEL-OFF: 1.5 + 0.5×1.2 = 2.1, not 2.7.
        var result = service.estimateForLines(
                List.of(line("1.5", "WHEEL-OFF", null, null), line("1.2", "WHEEL-OFF", null, null)));

        assertThat(result.estimatedHours()).isEqualByComparingTo("2.1");
    }

    @Test
    @DisplayName("a line whose operation another line's guide time includes contributes zero, and is flagged")
    void includedOperationContributesZero() {
        when(replicaRepository.findByServiceIdIn(any()))
                .thenReturn(List.of(
                        replica(PADS_SERVICE, "BRAKE-PAD-FRONT"), replica(ROTORS_SERVICE, "BRAKE-ROTOR-FRONT-PAIR")));

        // Rotors (2.0h) include the pads; the pads line (1.5h) is already paid for.
        var result = service.estimateForLines(
                List.of(line("2.0", null, "BRAKE-PAD-FRONT", ROTORS_SERVICE), line("1.5", null, null, PADS_SERVICE)));

        assertThat(result.estimatedHours()).isEqualByComparingTo("2.0");
        assertThat(result.includedOperationCodes()).containsExactly("BRAKE-PAD-FRONT");
    }

    @Test
    @DisplayName("declined and cancelled lines never count")
    void declinedAndCancelledExcluded() {
        WorkorderServiceLine declined = line("9.9", null, null, null);
        declined.setDeclined(true);
        WorkorderServiceLine cancelled = line("8.8", null, null, null);
        cancelled.setStatus(WorkorderItemStatus.CANCELLED);

        var result = service.estimateForLines(List.of(declined, cancelled, line("1.0", null, null, null)));

        assertThat(result.estimatedHours()).isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("no countable lines is null, not zero — the field must not claim an estimate that was never made")
    void noLinesIsNull() {
        assertThat(service.estimateForLines(List.of()).estimatedHours()).isNull();
        assertThat(service.estimateForLines(null).estimatedHours()).isNull();
    }

    @Test
    @DisplayName("two distinct overlap groups each contribute max plus factored rest, independently")
    void distinctOverlapGroupsIndependent() {
        // WHEEL-OFF: 2.0 + 0.5×1.0 = 2.5; UNDER-HOOD: 3.0 + 0.5×1.0 = 3.5.
        var result = service.estimateForLines(List.of(
                line("2.0", "WHEEL-OFF", null, null),
                line("1.0", "WHEEL-OFF", null, null),
                line("3.0", "UNDER-HOOD", null, null),
                line("1.0", "UNDER-HOOD", null, null)));

        assertThat(result.estimatedHours()).isEqualByComparingTo("6.0");
    }

    @Test
    @DisplayName("a line both included-by-another and in an overlap group is zeroed and leaves its group")
    void includedLineContributesNothingToItsOverlapGroup() {
        when(replicaRepository.findByServiceIdIn(any())).thenReturn(List.of(replica(PADS_SERVICE, "BRAKE-PAD-FRONT")));

        // Rotors (2.0) include the pads; the pads line (1.5) sits in WHEEL-OFF with a 1.0 line.
        // Zeroed, the pads must not even count as a group member: WHEEL-OFF is just the 1.0
        // line, so the total is 2.0 + 1.0 = 3.0 — not 2.0 + (1.5 + 0.5×1.0) = 4.0.
        var result = service.estimateForLines(List.of(
                line("2.0", null, "BRAKE-PAD-FRONT", ROTORS_SERVICE),
                line("1.5", "WHEEL-OFF", null, PADS_SERVICE),
                line("1.0", "WHEEL-OFF", null, null)));

        assertThat(result.estimatedHours()).isEqualByComparingTo("3.0");
        assertThat(result.includedOperationCodes()).containsExactly("BRAKE-PAD-FRONT");
    }

    @Test
    @DisplayName("a zero-quantity line counts as 0 hours but is not dropped — the estimate is 0.0, not null")
    void zeroQuantityLineCountsAsZeroNotDropped() {
        assertThat(service.estimateForLines(List.of(line("0", null, null, null)))
                        .estimatedHours())
                .isEqualByComparingTo("0.0");

        assertThat(service.estimateForLines(List.of(line("0", null, null, null), line("1.0", null, null, null)))
                        .estimatedHours())
                .isEqualByComparingTo("1.0");
    }

    @Test
    @DisplayName("the overlap factor is configuration, not a constant")
    void nonDefaultOverlapFactor() {
        ReflectionTestUtils.setField(service, "overlapAdditionalFactor", new BigDecimal("0.25"));

        // 2.0 + 0.25×1.0 = 2.25, rounded to tenths half-up → 2.3.
        var result = service.estimateForLines(
                List.of(line("2.0", "WHEEL-OFF", null, null), line("1.0", "WHEEL-OFF", null, null)));

        assertThat(result.estimatedHours()).isEqualByComparingTo("2.3");
    }

    @Test
    @DisplayName("chained include: a zeroed line's own include list never cascades")
    void chainedIncludeDoesNotCascade() {
        UUID cService = UUID.fromString("00000000-0000-0000-0000-0000000000a3");
        when(replicaRepository.findByServiceIdIn(any()))
                .thenReturn(List.of(replica(PADS_SERVICE, "OP-B"), replica(cService, "OP-C")));

        // A (3.0) includes OP-B; B (2.0, op OP-B) includes OP-C; C (1.0, op OP-C).
        // Greedy largest-first: A charges and zeroes B, but B — no longer charged — must not
        // zero C. Total 3.0 + 1.0 = 4.0, and only OP-B reports as included.
        var result = service.estimateForLines(List.of(
                line("3.0", null, "OP-B", ROTORS_SERVICE),
                line("2.0", null, "OP-C", PADS_SERVICE),
                line("1.0", null, null, cService)));

        assertThat(result.estimatedHours()).isEqualByComparingTo("4.0");
        assertThat(result.includedOperationCodes()).containsExactly("OP-B");
    }

    @Test
    @DisplayName("mutual include: the larger line survives, the smaller is zeroed")
    void mutualIncludeLargerLineSurvives() {
        when(replicaRepository.findByServiceIdIn(any()))
                .thenReturn(List.of(replica(ROTORS_SERVICE, "OP-A"), replica(PADS_SERVICE, "OP-B")));

        // A (2.0, op OP-A) includes OP-B; B (1.5, op OP-B) includes OP-A. The greedy pass
        // charges A first, so B is zeroed and B's include of OP-A never takes effect.
        var result = service.estimateForLines(
                List.of(line("2.0", null, "OP-B", ROTORS_SERVICE), line("1.5", null, "OP-A", PADS_SERVICE)));

        assertThat(result.estimatedHours()).isEqualByComparingTo("2.0");
        assertThat(result.includedOperationCodes()).containsExactly("OP-B");
    }

    private static ExtCatalogServiceReplica replica(UUID serviceId, String opCode) {
        return ExtCatalogServiceReplica.builder()
                .serviceId(serviceId)
                .operationCode(opCode)
                .active(true)
                .aggregateVersion(1L)
                .updatedAt(Instant.parse("2026-09-01T00:00:00Z"))
                .build();
    }
}
