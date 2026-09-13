package com.positivity.workorder.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.workorder.PostgresSliceTestBase;
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
import org.springframework.data.domain.PageRequest;

/**
 * The E7 date-range finder (#1595) against the real PostgreSQL baseline.
 *
 * <p>It runs on PostgreSQL rather than the H2 schema it used to boot because what it now also pins
 * is a property of PostgreSQL. {@code findByTransitionedAtRangeAndStatuses} is one JPQL string
 * containing two {@code (:param IS NULL OR column = :param)} clauses, and it is deliberately left
 * that way: PostgreSQL rejects that shape at parse time only when the placeholder's type cannot be
 * inferred, which is the case for a temporal parameter and not for these two — both are enums, bound
 * as varchar with a concrete type OID for a value and for {@code setNull} alike (issue #1891). The
 * query works, so it was not rewritten. Its {@code start}/{@code end} bounds are temporal but
 * mandatory, compared directly with no {@code IS NULL} branch, which is why they infer fine too.
 *
 * <p>What this pins is that it goes on working. Making either bound optional, or adding an optional
 * filter of any temporal type, would break every call to {@code GET /v1/workorders/status-transitions}
 * and the E5/E6 analytics endpoints built on it — and only a statement issued to PostgreSQL can see
 * it.
 */
@DisplayName("Workorder state-transition range finder on PostgreSQL")
class WorkorderStateTransitionRepositoryTest extends PostgresSliceTestBase {

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
        // workorder.workorder_number is NOT NULL in the baseline; the H2 schema this test used to
        // boot was generated from the mapping, which does not say so, and let the column go unset.
        workorder.setWorkorderNumber("WO-2026-TRANSITIONS");
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
