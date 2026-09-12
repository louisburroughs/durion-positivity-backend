package com.positivity.customer.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.customer.PostgresSliceTestBase;
import com.positivity.customer.TestClockConfig;
import com.positivity.customer.internal.config.JpaAuditingConfig;
import com.positivity.customer.internal.entity.FollowUpTask;
import com.positivity.customer.internal.enums.FollowUpStatus;
import com.positivity.customer.internal.enums.FollowUpType;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The CSR queue search ({@link FollowUpTaskRepository#findQueue}) against the real PostgreSQL
 * schema, exercising every combination of its all-optional filters.
 *
 * <p>This is a database test rather than a mocked one because what it guards is a property of the
 * database: the query used to be one JPQL string of {@code (:param IS NULL OR column = :param)}
 * clauses, which PostgreSQL rejects at parse time for every call (issue #1891, PR #1961) while H2
 * accepts it. Only a test that issues the statement to PostgreSQL can see that.
 */
@Import({JpaAuditingConfig.class, TestClockConfig.class})
@DisplayName("Follow-up queue search on PostgreSQL")
class FollowUpTaskRepositoryTest extends PostgresSliceTestBase {

    private static final String ALICE = "alice";
    private static final String BOB = "bob";

    @Autowired
    private FollowUpTaskRepository tasks;

    /**
     * {@code follow_up_task_outcome_chk} in the baseline requires every closed task to carry an
     * outcome, so a fixture that closes one has to record why — the H2 schema had no such
     * constraint and let a DONE task with no outcome exist, which the application never can.
     */
    private FollowUpTask task(FollowUpStatus status, String assignedTo, FollowUpType type, LocalDate dueDate) {
        return tasks.saveAndFlush(FollowUpTask.builder()
                .partyId(UUID.randomUUID())
                .type(type)
                .status(status)
                .outcome(status.isClosed() ? "worked" : null)
                .assignedTo(assignedTo)
                .dueDate(dueDate)
                .build());
    }

    @Test
    @DisplayName("an unfiltered queue pages every task")
    void unfilteredQueueReturnsEveryTask() {
        task(FollowUpStatus.OPEN, ALICE, FollowUpType.GENERAL, LocalDate.of(2026, 3, 1));
        task(FollowUpStatus.DONE, BOB, FollowUpType.FLEET_CHECKIN, LocalDate.of(2026, 3, 2));

        Page<FollowUpTask> page = tasks.findQueue(null, null, null, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(2);
    }

    @Test
    @DisplayName("each filter narrows the queue on its own")
    void eachFilterNarrowsTheQueueOnItsOwn() {
        FollowUpTask open = task(FollowUpStatus.OPEN, ALICE, FollowUpType.DECLINED_SERVICE_FOLLOWUP, null);
        task(FollowUpStatus.DONE, BOB, FollowUpType.FLEET_CHECKIN, null);

        assertThat(tasks.findQueue(FollowUpStatus.OPEN, null, null, PageRequest.of(0, 10))
                        .getContent())
                .containsExactly(open);
        assertThat(tasks.findQueue(null, ALICE, null, PageRequest.of(0, 10)).getContent())
                .containsExactly(open);
        assertThat(tasks.findQueue(null, null, FollowUpType.DECLINED_SERVICE_FOLLOWUP, PageRequest.of(0, 10))
                        .getContent())
                .containsExactly(open);
    }

    @Test
    @DisplayName("the filters combine")
    void filtersCombine() {
        FollowUpTask wanted = task(FollowUpStatus.OPEN, ALICE, FollowUpType.CAMPAIGN_RESPONSE, null);
        task(FollowUpStatus.OPEN, BOB, FollowUpType.CAMPAIGN_RESPONSE, null);
        task(FollowUpStatus.DONE, ALICE, FollowUpType.CAMPAIGN_RESPONSE, null);

        assertThat(tasks.findQueue(FollowUpStatus.OPEN, ALICE, FollowUpType.CAMPAIGN_RESPONSE, PageRequest.of(0, 10))
                        .getContent())
                .containsExactly(wanted);
    }

    @Test
    @DisplayName("tasks without a due date sort after those that have one")
    void undatedTasksSortLast() {
        FollowUpTask undated = task(FollowUpStatus.OPEN, ALICE, FollowUpType.GENERAL, null);
        FollowUpTask later = task(FollowUpStatus.OPEN, ALICE, FollowUpType.GENERAL, LocalDate.of(2026, 5, 1));
        FollowUpTask sooner = task(FollowUpStatus.OPEN, ALICE, FollowUpType.GENERAL, LocalDate.of(2026, 4, 1));

        // NULLS LAST is the point: PostgreSQL sorts nulls last on ASC by default and H2 sorts them
        // first, so an undated task's place in the worklist is a dialect behaviour, not a mapping.
        assertThat(tasks.findQueue(null, ALICE, null, PageRequest.of(0, 10)).getContent())
                .containsExactly(sooner, later, undated);
    }

    @Test
    @DisplayName("an unpaged queue request returns every match rather than failing")
    void unpagedQueueRequestReturnsEveryMatch() {
        task(FollowUpStatus.OPEN, ALICE, FollowUpType.GENERAL, null);
        task(FollowUpStatus.OPEN, BOB, FollowUpType.GENERAL, null);

        // Pageable.unpaged() reports a page size of zero, which PageRequest.of rejects; a search
        // that rebuilds the pageable has to carry the unpaged case through rather than throw.
        assertThat(tasks.findQueue(null, null, null, Pageable.unpaged()).getContent())
                .hasSize(2);
    }
}
