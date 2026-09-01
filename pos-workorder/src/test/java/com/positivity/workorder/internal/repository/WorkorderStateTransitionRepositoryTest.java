package com.positivity.workorder.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.internal.entity.Workorder;
import com.positivity.workorder.internal.entity.WorkorderStateTransition;
import com.positivity.workorder.internal.enums.WorkorderStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.PageRequest;

/**
 * DataJpaTest for the E7 date-range finder (#1595). {@code work_order_state_transitions} already
 * existed; this proves only the new query method against a real (H2) JPA provider.
 */
@DataJpaTest(properties = {"spring.flyway.enabled=false"})
class WorkorderStateTransitionRepositoryTest {

    @Autowired
    private WorkorderStateTransitionRepository repository;

    @Autowired
    private WorkorderRepository workorderRepository;

    private UUID workorderId;

    @BeforeEach
    void setUp() {
        // createdAt/updatedAt are @CreatedDate/@LastModifiedDate; JpaAuditingConfig (and its Clock
        // bean) is not on this slice's context, so both are set explicitly rather than relying on
        // auditing to populate the NOT NULL columns.
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        Workorder workorder = new Workorder();
        workorder.setStatus(WorkorderStatus.DRAFT);
        workorder.setCreatedAt(now);
        workorder.setUpdatedAt(now);
        workorder = workorderRepository.save(workorder);
        workorderId = workorder.getId();
    }

    private WorkorderStateTransition transition(WorkorderStatus from, WorkorderStatus to, Instant at, String actor) {
        // A plain `new Workorder(workorderId)` (production's shorthand for an FK-only reference)
        // reads as transient rather than detached to Hibernate outside the transaction that
        // actually loaded it, so this test uses the managed instance from the repository instead.
        Workorder managedWorkorder = workorderRepository.findById(workorderId).orElseThrow();
        return repository.save(WorkorderStateTransition.builder()
                .workorder(managedWorkorder)
                .fromStatus(from)
                .toStatus(to)
                .transitionedAt(at)
                .transitionedBy(actor)
                .createdAt(at)
                .updatedAt(at)
                .build());
    }

    @Test
    @DisplayName("Range finder is ordered oldest-first and honors the half-open [start, end) bound")
    void rangeFinderOrdersAscendingAndBoundsHalfOpen() {
        transition(WorkorderStatus.DRAFT, WorkorderStatus.APPROVED, Instant.parse("2026-06-01T00:00:00Z"), "advisor-1");
        transition(
                WorkorderStatus.APPROVED,
                WorkorderStatus.WORK_IN_PROGRESS,
                Instant.parse("2026-06-15T00:00:00Z"),
                "tech-1");
        transition(
                WorkorderStatus.WORK_IN_PROGRESS,
                WorkorderStatus.COMPLETED,
                Instant.parse("2026-06-30T23:59:59Z"),
                "tech-1");
        // Outside the queried window.
        transition(
                WorkorderStatus.COMPLETED, WorkorderStatus.COMPLETED, Instant.parse("2026-07-01T00:00:00Z"), "tech-1");

        List<WorkorderStateTransition> rows = repository.findByTransitionedAtRangeAndStatuses(
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                null,
                null,
                PageRequest.of(0, 100));

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).getToStatus()).isEqualTo(WorkorderStatus.APPROVED);
        assertThat(rows.get(1).getToStatus()).isEqualTo(WorkorderStatus.WORK_IN_PROGRESS);
        assertThat(rows.get(2).getToStatus()).isEqualTo(WorkorderStatus.COMPLETED);
    }

    @Test
    @DisplayName("fromStatus/toStatus filters narrow the range finder; null matches any")
    void rangeFinderFiltersByFromAndToStatus() {
        transition(WorkorderStatus.DRAFT, WorkorderStatus.APPROVED, Instant.parse("2026-06-01T00:00:00Z"), "advisor-1");
        transition(
                WorkorderStatus.APPROVED,
                WorkorderStatus.WORK_IN_PROGRESS,
                Instant.parse("2026-06-02T00:00:00Z"),
                "tech-1");
        transition(
                WorkorderStatus.WORK_IN_PROGRESS,
                WorkorderStatus.COMPLETED,
                Instant.parse("2026-06-03T00:00:00Z"),
                "tech-1");

        List<WorkorderStateTransition> onlyToCompleted = repository.findByTransitionedAtRangeAndStatuses(
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                null,
                WorkorderStatus.COMPLETED,
                PageRequest.of(0, 100));
        assertThat(onlyToCompleted).hasSize(1);
        assertThat(onlyToCompleted.get(0).getFromStatus()).isEqualTo(WorkorderStatus.WORK_IN_PROGRESS);

        List<WorkorderStateTransition> onlyFromApproved = repository.findByTransitionedAtRangeAndStatuses(
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                WorkorderStatus.APPROVED,
                null,
                PageRequest.of(0, 100));
        assertThat(onlyFromApproved).hasSize(1);
        assertThat(onlyFromApproved.get(0).getToStatus()).isEqualTo(WorkorderStatus.WORK_IN_PROGRESS);
    }

    @Test
    @DisplayName("Pageable size caps the row count without changing the ORDER BY")
    void pageableCapsRowCount() {
        for (int i = 0; i < 5; i++) {
            transition(
                    WorkorderStatus.DRAFT,
                    WorkorderStatus.APPROVED,
                    Instant.parse("2026-06-0" + (i + 1) + "T00:00:00Z"),
                    "advisor-1");
        }

        List<WorkorderStateTransition> capped = repository.findByTransitionedAtRangeAndStatuses(
                Instant.parse("2026-06-01T00:00:00Z"),
                Instant.parse("2026-07-01T00:00:00Z"),
                null,
                null,
                PageRequest.of(0, 3));

        assertThat(capped).hasSize(3);
        assertThat(capped.get(0).getTransitionedAt()).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"));
    }

    @Test
    @DisplayName("findByWorkorder_IdOrderByTransitionedAtAsc is oldest-first, the mirror of the existing desc finder")
    void woIdFinderOrdersAscending() {
        transition(
                WorkorderStatus.WORK_IN_PROGRESS,
                WorkorderStatus.COMPLETED,
                Instant.parse("2026-06-10T00:00:00Z"),
                "tech-1");
        transition(WorkorderStatus.DRAFT, WorkorderStatus.APPROVED, Instant.parse("2026-06-01T00:00:00Z"), "advisor-1");

        List<WorkorderStateTransition> rows = repository.findByWorkorder_IdOrderByTransitionedAtAsc(workorderId);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getToStatus()).isEqualTo(WorkorderStatus.APPROVED);
        assertThat(rows.get(1).getToStatus()).isEqualTo(WorkorderStatus.COMPLETED);
    }
}
