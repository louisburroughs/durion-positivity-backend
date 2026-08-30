package com.positivity.people.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.people.internal.config.JpaAuditingConfig;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

/**
 * {@link TimeEntryRepository#findForApprovalQueue} against a real database (#1573).
 *
 * <h2>Why this runs against a database rather than a mock</h2>
 *
 * The query is the whole feature: optional filters expressed as {@code :param IS NULL OR ...}, a
 * half-open instant window, {@code NULLS LAST} ordering and a hand-written count query. None of
 * that is exercised by a mocked repository — a typo in the JPQL, an untyped parameter, or a count
 * query that drifts from the selection all fail only when a database parses them.
 */
@DataJpaTest(
        properties = {
            "spring.datasource.url=jdbc:h2:mem:pos_people_queue;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
            // Flyway off, schema from the entities: the module's documented test footing, because
            // the Postgres-only baseline SQL will not run on H2 (see TimeEntryExceptionAuditingTest).
            "spring.flyway.enabled=false",
            "spring.jpa.hibernate.ddl-auto=create-drop"
        })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({JpaAuditingConfig.class, TimeEntryApprovalQueueTest.ClockConfig.class})
@DisplayName("TimeEntryRepository.findForApprovalQueue — filters, order and paging")
class TimeEntryApprovalQueueTest {

    private static final Instant DAY_START = Instant.parse("2026-01-15T00:00:00Z");

    private static final Instant DAY_END = Instant.parse("2026-01-16T00:00:00Z");

    private static final Instant UNBOUNDED_START = Instant.parse("0001-01-01T00:00:00Z");

    private static final Instant UNBOUNDED_END = Instant.parse("9999-12-31T23:59:59Z");

    private static final UUID ALICE = UUID.fromString("018f0a1b-0000-7000-8000-00000000a11c");

    private static final UUID BOB = UUID.fromString("018f0a1b-0000-7000-8000-00000000b0b0");

    private static final UUID SHOP_A = UUID.fromString("018f0a1b-0000-7000-8000-0000000005a1");

    private static final UUID SHOP_B = UUID.fromString("018f0a1b-0000-7000-8000-0000000005b2");

    @Autowired
    private TimeEntryRepository repository;

    private UUID aliceToday;

    private UUID bobToday;

    private UUID aliceYesterday;

    private UUID approvedToday;

    @BeforeEach
    void seed() {
        repository.deleteAll();
        // Alice is inserted first but submitted later, so the ordering assertions below prove the
        // queue sorts by submission rather than by insertion order or id.
        aliceToday =
                save(ALICE, SHOP_A, "2026-01-15T14:00:00Z", "2026-01-15T22:35:00Z", TimeEntryStatus.PENDING_APPROVAL);
        bobToday = save(BOB, SHOP_B, "2026-01-15T15:00:00Z", "2026-01-15T22:05:00Z", TimeEntryStatus.PENDING_APPROVAL);
        aliceYesterday =
                save(ALICE, SHOP_A, "2026-01-14T14:00:00Z", "2026-01-14T22:00:00Z", TimeEntryStatus.PENDING_APPROVAL);
        approvedToday = save(ALICE, SHOP_A, "2026-01-15T16:00:00Z", "2026-01-15T23:00:00Z", TimeEntryStatus.APPROVED);
    }

    private UUID save(UUID personId, UUID locationId, String startAt, String submittedAt, TimeEntryStatus status) {
        TimeEntry entry = new TimeEntry();
        entry.setPersonId(personId);
        entry.setLocationId(locationId);
        entry.setAttendanceStartAt(Instant.parse(startAt));
        entry.setAttendanceEndAt(Instant.parse(startAt).plusSeconds(8L * 3600));
        entry.setBreakMinutes(30);
        entry.setStatus(status);
        entry.setSubmittedAt(Instant.parse(submittedAt));
        return repository.save(entry).getTimeEntryId();
    }

    private List<UUID> idsOf(Page<TimeEntry> page) {
        return page.getContent().stream().map(TimeEntry::getTimeEntryId).toList();
    }

    @Test
    @DisplayName("no filters returns every entry")
    void noFiltersReturnsEverything() {
        Page<TimeEntry> page = repository.findForApprovalQueue(
                null, null, null, UNBOUNDED_START, UNBOUNDED_END, PageRequest.of(0, 20));

        assertThat(idsOf(page)).containsExactlyInAnyOrder(aliceToday, bobToday, aliceYesterday, approvedToday);
    }

    @Test
    @DisplayName("the day window keeps that day's entries and drops the day before")
    void dayWindowExcludesOtherDays() {
        Page<TimeEntry> page =
                repository.findForApprovalQueue(null, null, null, DAY_START, DAY_END, PageRequest.of(0, 20));

        assertThat(idsOf(page)).containsExactlyInAnyOrder(aliceToday, bobToday, approvedToday);
    }

    @Test
    @DisplayName("the window's end is exclusive, so midnight belongs to the next day")
    void windowEndIsExclusive() {
        UUID midnight =
                save(ALICE, SHOP_A, "2026-01-16T00:00:00Z", "2026-01-16T08:00:00Z", TimeEntryStatus.PENDING_APPROVAL);

        Page<TimeEntry> onTheFifteenth =
                repository.findForApprovalQueue(null, null, null, DAY_START, DAY_END, PageRequest.of(0, 20));
        Page<TimeEntry> onTheSixteenth = repository.findForApprovalQueue(
                null, null, null, DAY_END, DAY_END.plusSeconds(86400), PageRequest.of(0, 20));

        assertThat(idsOf(onTheFifteenth)).doesNotContain(midnight);
        assertThat(idsOf(onTheSixteenth)).containsExactly(midnight);
    }

    @Test
    @DisplayName("status narrows to the entries an approver can act on")
    void statusFilterSelectsPendingOnly() {
        Page<TimeEntry> page = repository.findForApprovalQueue(
                TimeEntryStatus.PENDING_APPROVAL, null, null, DAY_START, DAY_END, PageRequest.of(0, 20));

        assertThat(idsOf(page)).containsExactlyInAnyOrder(aliceToday, bobToday);
    }

    @Test
    @DisplayName("person and location each narrow independently")
    void personAndLocationFilters() {
        Page<TimeEntry> byPerson =
                repository.findForApprovalQueue(null, BOB, null, UNBOUNDED_START, UNBOUNDED_END, PageRequest.of(0, 20));
        Page<TimeEntry> byLocation = repository.findForApprovalQueue(
                null, null, SHOP_B, UNBOUNDED_START, UNBOUNDED_END, PageRequest.of(0, 20));

        assertThat(idsOf(byPerson)).containsExactly(bobToday);
        assertThat(idsOf(byLocation)).containsExactly(bobToday);
    }

    @Test
    @DisplayName("filters combine, so a person at a location they never worked returns nothing")
    void filtersCombine() {
        Page<TimeEntry> page = repository.findForApprovalQueue(
                null, BOB, SHOP_A, UNBOUNDED_START, UNBOUNDED_END, PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    @DisplayName("the queue is ordered oldest submission first")
    void orderedByOldestSubmissionFirst() {
        Page<TimeEntry> page = repository.findForApprovalQueue(
                TimeEntryStatus.PENDING_APPROVAL, null, null, DAY_START, DAY_END, PageRequest.of(0, 20));

        // Bob submitted at 22:05, Alice at 22:35, and Alice's row was inserted first.
        assertThat(idsOf(page)).containsExactly(bobToday, aliceToday);
    }

    @Test
    @DisplayName("an unsubmitted entry sorts after every submitted one rather than jumping the queue")
    void unsubmittedEntriesSortLast() {
        TimeEntry draft = new TimeEntry();
        draft.setPersonId(ALICE);
        draft.setLocationId(SHOP_A);
        draft.setAttendanceStartAt(Instant.parse("2026-01-15T08:00:00Z"));
        draft.setStatus(TimeEntryStatus.DRAFT);
        UUID draftId = repository.save(draft).getTimeEntryId();

        Page<TimeEntry> page =
                repository.findForApprovalQueue(null, null, null, DAY_START, DAY_END, PageRequest.of(0, 20));

        assertThat(idsOf(page)).endsWith(draftId);
    }

    @Test
    @DisplayName("paging reports totals from the count query and hands back one page of rows")
    void pagingReportsTotals() {
        Page<TimeEntry> firstPage =
                repository.findForApprovalQueue(null, null, null, DAY_START, DAY_END, PageRequest.of(0, 2));
        Page<TimeEntry> secondPage =
                repository.findForApprovalQueue(null, null, null, DAY_START, DAY_END, PageRequest.of(1, 2));

        assertThat(firstPage.getTotalElements()).isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(2);
        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(idsOf(firstPage)).doesNotContainAnyElementsOf(idsOf(secondPage));
    }

    @Test
    @DisplayName("an entry with no clock-in is not in the queue: it has no day to be approved for")
    void entryWithoutClockInIsNotQueued() {
        TimeEntry noClockIn = new TimeEntry();
        noClockIn.setPersonId(ALICE);
        noClockIn.setStatus(TimeEntryStatus.PENDING_APPROVAL);
        noClockIn.setSubmittedAt(Instant.parse("2026-01-15T22:00:00Z"));
        UUID id = repository.save(noClockIn).getTimeEntryId();

        Page<TimeEntry> page = repository.findForApprovalQueue(
                null, null, null, UNBOUNDED_START, UNBOUNDED_END, PageRequest.of(0, 20));

        assertThat(idsOf(page)).doesNotContain(id);
    }

    /** {@link JpaAuditingConfig} needs a clock; the values it stamps are not what this test asserts. */
    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }
}
