package com.positivity.workorder.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.workorder.internal.dto.ReopenedWorkorderAnalyticsResponse;
import com.positivity.workorder.internal.dto.TechnicianLaborAnalyticsResponse;
import com.positivity.workorder.internal.dto.TechnicianLaborRow;
import com.positivity.workorder.internal.dto.WorkorderStatusTransitionsResponse;
import com.positivity.workorder.internal.entity.ExtInvoiceReplica;
import com.positivity.workorder.internal.entity.ExtPersonReplica;
import com.positivity.workorder.internal.entity.ExtUserLinkReplica;
import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderLaborEntry;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import com.positivity.workorder.internal.repository.ExtInvoiceReplicaRepository;
import com.positivity.workorder.internal.repository.ExtPersonReplicaRepository;
import com.positivity.workorder.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.workorder.internal.repository.WorkorderLaborEntryRepository;
import com.positivity.workorder.internal.repository.WorkorderStateTransitionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

class WorkorderAnalyticsServiceImplTest {

    private static final UUID WO_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID WO_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID TECH_A = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final UUID TECH_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    private final WorkorderStateTransitionRepository transitionRepository =
            mock(WorkorderStateTransitionRepository.class);
    private final WorkorderLaborEntryRepository laborEntryRepository = mock(WorkorderLaborEntryRepository.class);
    private final ExtInvoiceReplicaRepository invoiceReplicaRepository = mock(ExtInvoiceReplicaRepository.class);
    private final ExtPersonReplicaRepository personReplicaRepository = mock(ExtPersonReplicaRepository.class);
    private final ExtUserLinkReplicaRepository userLinkReplicaRepository = mock(ExtUserLinkReplicaRepository.class);

    private WorkorderAnalyticsServiceImpl service;
    private final List<ExtUserLinkReplica> activeLinks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        service = new WorkorderAnalyticsServiceImpl(
                transitionRepository,
                laborEntryRepository,
                invoiceReplicaRepository,
                personReplicaRepository,
                userLinkReplicaRepository);
        // Default: no labor entries / invoices / person replicas / user links unless a test
        // overrides (via link()) or adds its own stub.
        when(laborEntryRepository.findByEndTimeIsNotNullAndStartTimeBetween(any(), any()))
                .thenReturn(List.of());
        when(invoiceReplicaRepository.findByWorkorderIdIn(any())).thenReturn(List.of());
        when(personReplicaRepository.findByPersonIdIn(any())).thenReturn(List.of());
        when(userLinkReplicaRepository.findByUsernameInAndStatus(any(), eq("ACTIVE")))
                .thenAnswer(invocation -> List.copyOf(activeLinks));
    }

    private static WorkorderStateTransition transition(
            UUID workorderId, WorkorderStatus from, WorkorderStatus to, Instant at, String actor) {
        return WorkorderStateTransition.builder()
                .workorder(new Workorder(workorderId))
                .fromStatus(from)
                .toStatus(to)
                .transitionedAt(at)
                .transitionedBy(actor)
                .build();
    }

    private static WorkorderLaborEntry laborEntry(UUID technicianId, BigDecimal hoursWorked) {
        return WorkorderLaborEntry.builder()
                .workorder(new Workorder(WO_1))
                .workorderService(new com.positivity.workorder.internal.entity.WorkorderServiceLine(UUID.randomUUID()))
                .technicianId(technicianId)
                .startTime(LocalDateTime.parse("2026-06-15T09:00:00"))
                .endTime(LocalDateTime.parse("2026-06-15T12:00:00"))
                .hoursWorked(hoursWorked)
                .createdBy("system")
                .build();
    }

    private void link(String username, UUID personId) {
        activeLinks.add(ExtUserLinkReplica.builder()
                .linkId(UUID.randomUUID())
                .personId(personId)
                .username(username)
                .status("ACTIVE")
                .build());
    }

    // ---------------------------------------------------------------- getStatusTransitions (E7)

    @Test
    @DisplayName("woId together with a range param is rejected as ambiguous")
    void statusTransitions_woIdAndRangeParams_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getStatusTransitions(WO_1, null, null, LocalDate.of(2026, 6, 1), null, 100));
    }

    @Test
    @DisplayName("Neither woId nor any range param is rejected as an empty combination")
    void statusTransitions_emptyCombination_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getStatusTransitions(null, null, null, null, null, 100));
    }

    @Test
    @DisplayName("Range mode with only endDate (no startDate) is rejected")
    void statusTransitions_rangeModeMissingStartDate_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getStatusTransitions(null, null, null, null, LocalDate.of(2026, 6, 30), 100));
    }

    @Test
    @DisplayName("endDate before startDate is rejected")
    void statusTransitions_endBeforeStart_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getStatusTransitions(
                        null, null, null, LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1), 100));
    }

    @Test
    @DisplayName("woId mode delegates to the ascending per-workorder finder and reports truncation")
    void statusTransitions_woIdMode_truncatesAtLimit() {
        List<WorkorderStateTransition> all = List.of(
                transition(
                        WO_1,
                        WorkorderStatus.DRAFT,
                        WorkorderStatus.APPROVED,
                        Instant.parse("2026-06-01T00:00:00Z"),
                        "a"),
                transition(
                        WO_1,
                        WorkorderStatus.APPROVED,
                        WorkorderStatus.WORK_IN_PROGRESS,
                        Instant.parse("2026-06-02T00:00:00Z"),
                        "b"));
        when(transitionRepository.findByWorkorder_IdOrderByTransitionedAtAsc(WO_1))
                .thenReturn(all);

        WorkorderStatusTransitionsResponse response = service.getStatusTransitions(WO_1, null, null, null, null, 1);

        assertThat(response.isTruncated()).isTrue();
        assertThat(response.getLimit()).isEqualTo(1);
        assertThat(response.getTransitions()).hasSize(1);
        assertThat(response.getTransitions().get(0).getToStatus()).isEqualTo("APPROVED");
    }

    @Test
    @DisplayName("Range mode is not truncated when the row count is within limit")
    void statusTransitions_rangeMode_notTruncatedWithinLimit() {
        List<WorkorderStateTransition> page = List.of(transition(
                WO_1, WorkorderStatus.DRAFT, WorkorderStatus.APPROVED, Instant.parse("2026-06-01T00:00:00Z"), "a"));
        when(transitionRepository.findByTransitionedAtRangeAndStatuses(
                        any(Instant.class), any(Instant.class), isNull(), isNull(), any(Pageable.class)))
                .thenReturn(page);

        WorkorderStatusTransitionsResponse response = service.getStatusTransitions(
                null, null, null, LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 100);

        assertThat(response.isTruncated()).isFalse();
        assertThat(response.getTransitions()).hasSize(1);
    }

    // -------------------------------------------------------------- getReopenedWorkorders (E6)

    @Test
    @DisplayName("endDate before startDate is rejected")
    void reopened_endBeforeStart_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() ->
                        service.getReopenedWorkorders(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1), 7, 100));
    }

    @Test
    @DisplayName("A completion with no reopen marker produces no rows")
    void reopened_noMarkers_emptyRows() {
        Instant completedAt = Instant.parse("2026-06-10T00:00:00Z");
        WorkorderStateTransition completion =
                transition(WO_1, WorkorderStatus.WORK_IN_PROGRESS, WorkorderStatus.COMPLETED, completedAt, "tech-user");
        mockCompletionsAndMarkers(List.of(completion), List.of());
        link("tech-user", TECH_A);

        ReopenedWorkorderAnalyticsResponse response =
                service.getReopenedWorkorders(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 7, 100);

        assertThat(response.getRows()).isEmpty();
        assertThat(response.isTruncated()).isFalse();
    }

    @Test
    @DisplayName("Two reopens of the same workorder within withinDays each produce their own row (#1594 AC)")
    void reopened_multipleReopensSameWorkorder_eachProduceARow() {
        Instant completedAt = Instant.parse("2026-06-10T00:00:00Z");
        WorkorderStateTransition completion =
                transition(WO_1, WorkorderStatus.WORK_IN_PROGRESS, WorkorderStatus.COMPLETED, completedAt, "tech-user");
        WorkorderStateTransition reopen1 = transition(
                WO_1, WorkorderStatus.COMPLETED, WorkorderStatus.COMPLETED, completedAt.plusSeconds(3600), "advisor-1");
        WorkorderStateTransition reopen2 = transition(
                WO_1,
                WorkorderStatus.COMPLETED,
                WorkorderStatus.COMPLETED,
                completedAt.plusSeconds(2 * 86400L),
                "advisor-1");
        mockCompletionsAndMarkers(List.of(completion), List.of(reopen1, reopen2));
        link("tech-user", TECH_A);

        ReopenedWorkorderAnalyticsResponse response =
                service.getReopenedWorkorders(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 7, 100);

        assertThat(response.getRows()).hasSize(2);
        assertThat(response.getRows()).allSatisfy(row -> {
            assertThat(row.getWoId()).isEqualTo(WO_1);
            assertThat(row.getCompletedAt()).isEqualTo(completedAt);
            assertThat(row.getTechnicianId()).isEqualTo(TECH_A);
        });
        assertThat(response.getRows().get(0).getReopenedAt()).isEqualTo(reopen1.getTransitionedAt());
        assertThat(response.getRows().get(1).getReopenedAt()).isEqualTo(reopen2.getTransitionedAt());
    }

    @Test
    @DisplayName("A reopen marker beyond withinDays of its completion is excluded")
    void reopened_markerBeyondWithinDays_excluded() {
        Instant completedAt = Instant.parse("2026-06-10T00:00:00Z");
        WorkorderStateTransition completion =
                transition(WO_1, WorkorderStatus.WORK_IN_PROGRESS, WorkorderStatus.COMPLETED, completedAt, "tech-user");
        WorkorderStateTransition lateReopen = transition(
                WO_1,
                WorkorderStatus.COMPLETED,
                WorkorderStatus.COMPLETED,
                completedAt.plusSeconds(8 * 86400L),
                "advisor-1");
        mockCompletionsAndMarkers(List.of(completion), List.of(lateReopen));
        link("tech-user", TECH_A);

        ReopenedWorkorderAnalyticsResponse response =
                service.getReopenedWorkorders(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 7, 100);

        assertThat(response.getRows()).isEmpty();
    }

    @Test
    @DisplayName(
            "A completing actor that cannot be resolved to a technician excludes the row (documented, not guessed)")
    void reopened_unresolvedCompletingActor_excluded() {
        Instant completedAt = Instant.parse("2026-06-10T00:00:00Z");
        WorkorderStateTransition completion = transition(
                WO_1, WorkorderStatus.WORK_IN_PROGRESS, WorkorderStatus.COMPLETED, completedAt, "unknown-user");
        WorkorderStateTransition reopen = transition(
                WO_1, WorkorderStatus.COMPLETED, WorkorderStatus.COMPLETED, completedAt.plusSeconds(3600), "advisor-1");
        mockCompletionsAndMarkers(List.of(completion), List.of(reopen));
        // No link() call -> findByUsernameInAndStatus returns empty for "unknown-user" (setUp default).

        ReopenedWorkorderAnalyticsResponse response =
                service.getReopenedWorkorders(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 7, 100);

        assertThat(response.getRows()).isEmpty();
    }

    @Test
    @DisplayName("A null transitionedBy on the completing transition excludes the row")
    void reopened_nullCompletingActor_excluded() {
        Instant completedAt = Instant.parse("2026-06-10T00:00:00Z");
        WorkorderStateTransition completion =
                transition(WO_1, WorkorderStatus.WORK_IN_PROGRESS, WorkorderStatus.COMPLETED, completedAt, null);
        WorkorderStateTransition reopen = transition(
                WO_1, WorkorderStatus.COMPLETED, WorkorderStatus.COMPLETED, completedAt.plusSeconds(3600), "advisor-1");
        mockCompletionsAndMarkers(List.of(completion), List.of(reopen));

        ReopenedWorkorderAnalyticsResponse response =
                service.getReopenedWorkorders(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 7, 100);

        assertThat(response.getRows()).isEmpty();
    }

    @Test
    @DisplayName("Rows are capped at limit and truncated is set")
    void reopened_truncatesAtLimit() {
        Instant completedAt = Instant.parse("2026-06-10T00:00:00Z");
        WorkorderStateTransition completion =
                transition(WO_1, WorkorderStatus.WORK_IN_PROGRESS, WorkorderStatus.COMPLETED, completedAt, "tech-user");
        WorkorderStateTransition reopen1 = transition(
                WO_1, WorkorderStatus.COMPLETED, WorkorderStatus.COMPLETED, completedAt.plusSeconds(3600), "advisor-1");
        WorkorderStateTransition reopen2 = transition(
                WO_1, WorkorderStatus.COMPLETED, WorkorderStatus.COMPLETED, completedAt.plusSeconds(7200), "advisor-1");
        mockCompletionsAndMarkers(List.of(completion), List.of(reopen1, reopen2));
        link("tech-user", TECH_A);

        ReopenedWorkorderAnalyticsResponse response =
                service.getReopenedWorkorders(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 7, 1);

        assertThat(response.getRows()).hasSize(1);
        assertThat(response.isTruncated()).isTrue();
    }

    /** Wires the two internal fetchRange() calls getReopenedWorkorders/getTechnicianLabor make: completions (toStatus=COMPLETED, fromStatus any) and reopen markers (COMPLETED->COMPLETED). */
    private void mockCompletionsAndMarkers(
            List<WorkorderStateTransition> completions, List<WorkorderStateTransition> markers) {
        when(transitionRepository.findByTransitionedAtRangeAndStatuses(
                        any(Instant.class),
                        any(Instant.class),
                        isNull(),
                        eq(WorkorderStatus.COMPLETED),
                        any(Pageable.class)))
                .thenReturn(completions);
        when(transitionRepository.findByTransitionedAtRangeAndStatuses(
                        any(Instant.class),
                        any(Instant.class),
                        eq(WorkorderStatus.COMPLETED),
                        eq(WorkorderStatus.COMPLETED),
                        any(Pageable.class)))
                .thenReturn(markers);
    }

    @Test
    @DisplayName(
            "truncated is true when the internal fetch cap was hit, even though the final row count is within limit "
                    + "(adversarial-review finding: an under-counted aggregate must not be reported as complete)")
    void reopened_truncatedWhenInternalFetchCapHit() {
        // INTERNAL_FETCH_CAP is 20_000 and private; this mirrors it rather than depending on the
        // constant so the test still catches a regression if that constant changes independently.
        List<WorkorderStateTransition> manyCompletions = new ArrayList<>();
        for (int i = 0; i < 20_000; i++) {
            manyCompletions.add(transition(
                    UUID.randomUUID(),
                    WorkorderStatus.WORK_IN_PROGRESS,
                    WorkorderStatus.COMPLETED,
                    Instant.parse("2026-06-10T00:00:00Z").plusSeconds(i),
                    "tech-user"));
        }
        mockCompletionsAndMarkers(manyCompletions, List.of());
        link("tech-user", TECH_A);

        ReopenedWorkorderAnalyticsResponse response =
                service.getReopenedWorkorders(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 7, 100);

        // No markers at all -> zero rows, well within limit=100 by row count alone.
        assertThat(response.getRows()).isEmpty();
        assertThat(response.isTruncated()).isTrue();
    }

    // ------------------------------------------------------------- getTechnicianLabor (E5)

    @Test
    @DisplayName("endDate before startDate is rejected")
    void technicianLabor_endBeforeStart_rejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> service.getTechnicianLabor(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 1), 100));
    }

    @Test
    @DisplayName("completedWoCount, billedHours and laborRevenue are independently windowed and unioned per technician")
    void technicianLabor_unionsThreeSignalsPerTechnician() {
        Instant completedAt = Instant.parse("2026-06-15T00:00:00Z");
        WorkorderStateTransition completionA = transition(
                WO_1, WorkorderStatus.WORK_IN_PROGRESS, WorkorderStatus.COMPLETED, completedAt, "tech-a-user");
        mockCompletionsAndMarkers(List.of(completionA), List.of());
        link("tech-a-user", TECH_A);

        // TECH_B logged hours this window but completed nothing in it.
        WorkorderLaborEntry entryA = laborEntry(TECH_A, new BigDecimal("3.00"));
        WorkorderLaborEntry entryB = laborEntry(TECH_B, new BigDecimal("5.50"));
        when(laborEntryRepository.findByEndTimeIsNotNullAndStartTimeBetween(
                        any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(entryA, entryB));

        when(invoiceReplicaRepository.findByWorkorderIdIn(Set.of(WO_1)))
                .thenReturn(List.of(ExtInvoiceReplica.builder()
                        .invoiceId(UUID.randomUUID())
                        .workorderId(WO_1)
                        .laborTotal(new BigDecimal("240.00"))
                        .build()));

        when(personReplicaRepository.findByPersonIdIn(any()))
                .thenReturn(List.of(
                        ExtPersonReplica.builder()
                                .personId(TECH_A)
                                .firstName("Ada")
                                .lastName("Tech")
                                .build(),
                        ExtPersonReplica.builder()
                                .personId(TECH_B)
                                .firstName("Bo")
                                .lastName("Wrench")
                                .build()));

        TechnicianLaborAnalyticsResponse response =
                service.getTechnicianLabor(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 100);

        TechnicianLaborRow rowA = response.getRows().stream()
                .filter(r -> r.getTechnicianId().equals(TECH_A))
                .findFirst()
                .orElseThrow();
        assertThat(rowA.getCompletedWoCount()).isEqualTo(1);
        assertThat(rowA.getBilledHours()).isEqualByComparingTo("3.00");
        assertThat(rowA.getLaborRevenue()).isEqualByComparingTo("240.00");
        assertThat(rowA.getName()).isEqualTo("Ada Tech");

        TechnicianLaborRow rowB = response.getRows().stream()
                .filter(r -> r.getTechnicianId().equals(TECH_B))
                .findFirst()
                .orElseThrow();
        assertThat(rowB.getCompletedWoCount()).isEqualTo(0);
        assertThat(rowB.getBilledHours()).isEqualByComparingTo("5.50");
        assertThat(rowB.getLaborRevenue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("An invoice with a null laborTotal is excluded from laborRevenue rather than treated as zero")
    void technicianLabor_nullLaborTotalInvoiceExcluded() {
        Instant completedAt = Instant.parse("2026-06-15T00:00:00Z");
        WorkorderStateTransition completionA = transition(
                WO_1, WorkorderStatus.WORK_IN_PROGRESS, WorkorderStatus.COMPLETED, completedAt, "tech-a-user");
        WorkorderStateTransition completionA2 = transition(
                WO_2, WorkorderStatus.WORK_IN_PROGRESS, WorkorderStatus.COMPLETED, completedAt, "tech-a-user");
        mockCompletionsAndMarkers(List.of(completionA, completionA2), List.of());
        link("tech-a-user", TECH_A);

        // WO_1's invoice has a known laborTotal; WO_2's invoice has laborTotal == null (unknown split).
        when(invoiceReplicaRepository.findByWorkorderIdIn(Set.of(WO_1, WO_2)))
                .thenReturn(List.of(
                        ExtInvoiceReplica.builder()
                                .invoiceId(UUID.randomUUID())
                                .workorderId(WO_1)
                                .laborTotal(new BigDecimal("100.00"))
                                .build(),
                        ExtInvoiceReplica.builder()
                                .invoiceId(UUID.randomUUID())
                                .workorderId(WO_2)
                                .laborTotal(null)
                                .build()));

        TechnicianLaborAnalyticsResponse response =
                service.getTechnicianLabor(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 100);

        TechnicianLaborRow rowA = response.getRows().stream()
                .filter(r -> r.getTechnicianId().equals(TECH_A))
                .findFirst()
                .orElseThrow();
        assertThat(rowA.getCompletedWoCount()).isEqualTo(2);
        // Only WO_1's 100.00 counts; WO_2's null-laborTotal invoice is excluded, not treated as 0.
        assertThat(rowA.getLaborRevenue()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("Rows are ordered by billedHours descending and truncated at limit")
    void technicianLabor_ordersByBilledHoursDescendingAndTruncates() {
        WorkorderLaborEntry entryA = laborEntry(TECH_A, new BigDecimal("2.00"));
        WorkorderLaborEntry entryB = laborEntry(TECH_B, new BigDecimal("9.00"));
        when(laborEntryRepository.findByEndTimeIsNotNullAndStartTimeBetween(
                        any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.of(entryA, entryB));
        mockCompletionsAndMarkers(List.of(), List.of());

        TechnicianLaborAnalyticsResponse response =
                service.getTechnicianLabor(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 30), 1);

        assertThat(response.isTruncated()).isTrue();
        assertThat(response.getRows()).hasSize(1);
        assertThat(response.getRows().get(0).getTechnicianId()).isEqualTo(TECH_B);
    }
}
