package com.positivity.people.internal.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.people.PostgresSliceTestBase;
import com.positivity.people.internal.entity.ExtJobTimeReplica;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The attendance and job-time window searches of the discrepancy report and the approvals queue,
 * against the real PostgreSQL schema.
 *
 * <h2>What this defends</h2>
 *
 * Every query here is still one JPQL string of {@code (:param IS NULL OR column = :param)} clauses
 * and is deliberately left that way: PostgreSQL rejects that shape at parse time only when the
 * placeholder's type cannot be inferred, which is the case for a temporal parameter and not for
 * these — a {@code UUID}, an enum and a {@code boolean}, each of which the driver binds with a
 * concrete type OID for a value and for a null alike (issue #1891). The queries work, so they were
 * not rewritten.
 *
 * <p>What this pins is that they go on working. Adding an optional filter of a type the driver
 * leaves untyped — any {@code Instant}, {@code LocalDate} or other temporal — would turn every call
 * to the discrepancy report and the approvals queue into a 500, and only a test that issues the
 * statement to PostgreSQL can see it. Both the bound and the unbound case of each optional filter is
 * exercised, because the failure is not always symmetric.
 *
 * <p>What decides it is inferability rather than the parameter's Java type: a placeholder fails when
 * the statement gives PostgreSQL nothing to infer from. A temporal lands there because pgjdbc sends
 * it with an unspecified OID, and a {@code String} lands there too when its only appearances are an
 * {@code IS NULL} test and a function call such as {@code upper(…)} — how pos-tax's exemption lookup
 * failed with {@code function upper(bytea) does not exist}. Each optional filter here is compared
 * against a column, which is what makes it inferable.
 *
 * <p>The window bounds themselves are never null: an absent day filter widens them to a bounding
 * instant instead, which is what keeps a temporal placeholder out of every {@code IS NULL} here.
 */
@DisplayName("Attendance window searches on PostgreSQL (#1891)")
class AttendanceWindowSearchesPostgresTest extends PostgresSliceTestBase {

    private static final Instant WINDOW_START = Instant.parse("2026-04-06T00:00:00Z");
    private static final Instant WINDOW_END = Instant.parse("2026-04-07T00:00:00Z");
    private static final Instant INSIDE_WINDOW = Instant.parse("2026-04-06T09:00:00Z");

    private static final UUID SHOP_A = UUID.fromString("018f0a1b-0000-7000-8000-0000000009a1");
    private static final UUID SHOP_B = UUID.fromString("018f0a1b-0000-7000-8000-0000000009b2");

    @Autowired
    private TimeEntryRepository timeEntries;

    @Autowired
    private ExtJobTimeReplicaRepository jobTimes;

    private TimeEntry attendance(UUID personId, UUID locationId, Instant startAt, TimeEntryStatus status) {
        TimeEntry entry = new TimeEntry();
        entry.setPersonId(personId);
        entry.setLocationId(locationId);
        entry.setAttendanceStartAt(startAt);
        entry.setAttendanceEndAt(startAt.plusSeconds(8L * 3600));
        entry.setBreakMinutes(30);
        entry.setStatus(status);
        entry.setSubmittedAt(startAt.plusSeconds(9L * 3600));
        return timeEntries.saveAndFlush(entry);
    }

    private ExtJobTimeReplica jobTime(UUID technicianId, UUID locationId, Instant endAtUtc) {
        ExtJobTimeReplica replica = new ExtJobTimeReplica();
        replica.setLaborEntryId(UUID.randomUUID());
        replica.setWorkOrderId(UUID.randomUUID());
        replica.setTechnicianId(technicianId);
        replica.setLocationId(locationId);
        replica.setEndAtUtc(endAtUtc);
        replica.setMinutes(120);
        replica.setUpdatedAt(endAtUtc);
        return jobTimes.saveAndFlush(replica);
    }

    @Nested
    @DisplayName("findAttendanceOverlappingWindow — the attendance side of the discrepancy report")
    class AttendanceOverlappingWindow {

        @Test
        @DisplayName("an absent location filter reads every location")
        void absentLocationReadsEveryLocation() {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            TimeEntry atA = attendance(alice, SHOP_A, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);
            TimeEntry atB = attendance(bob, SHOP_B, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);

            assertThat(timeEntries.findAttendanceOverlappingWindow(WINDOW_START, WINDOW_END, null, List.of(), true))
                    .contains(atA, atB);
        }

        @Test
        @DisplayName("a supplied location filter narrows to that location")
        void suppliedLocationNarrows() {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            TimeEntry atA = attendance(alice, SHOP_A, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);
            TimeEntry atB = attendance(bob, SHOP_B, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);

            assertThat(timeEntries.findAttendanceOverlappingWindow(WINDOW_START, WINDOW_END, SHOP_A, List.of(), true))
                    .contains(atA)
                    .doesNotContain(atB);
        }

        @Test
        @DisplayName("naming technicians narrows to them, rather than reading everyone")
        void namedTechniciansNarrow() {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            TimeEntry aliceEntry = attendance(alice, SHOP_A, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);
            TimeEntry bobEntry = attendance(bob, SHOP_A, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);

            assertThat(timeEntries.findAttendanceOverlappingWindow(
                            WINDOW_START, WINDOW_END, SHOP_A, List.of(alice), false))
                    .contains(aliceEntry)
                    .doesNotContain(bobEntry);
        }

        @Test
        @DisplayName("an entry outside the window is not overlapping it")
        void entryOutsideTheWindowIsExcluded() {
            UUID alice = UUID.randomUUID();
            TimeEntry outside =
                    attendance(alice, SHOP_A, WINDOW_END.plusSeconds(3600), TimeEntryStatus.PENDING_APPROVAL);

            assertThat(timeEntries.findAttendanceOverlappingWindow(WINDOW_START, WINDOW_END, null, List.of(), true))
                    .doesNotContain(outside);
        }

        @Test
        @DisplayName("the within-locations variant reads only the caller's reach")
        void withinLocationsReadsOnlyTheReach() {
            UUID alice = UUID.randomUUID();
            TimeEntry atA = attendance(alice, SHOP_A, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);
            TimeEntry atB = attendance(UUID.randomUUID(), SHOP_B, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);

            assertThat(timeEntries.findAttendanceOverlappingWindowWithinLocations(
                            WINDOW_START, WINDOW_END, List.of(SHOP_A), List.of(), true))
                    .contains(atA)
                    .doesNotContain(atB);
        }
    }

    @Nested
    @DisplayName("findForReportWindow — the job-time side of the discrepancy report")
    class JobTimeReportWindow {

        @Test
        @DisplayName("an absent location filter reads every location that has one")
        void absentLocationReadsEveryLocation() {
            ExtJobTimeReplica atA = jobTime(UUID.randomUUID(), SHOP_A, INSIDE_WINDOW);
            ExtJobTimeReplica atB = jobTime(UUID.randomUUID(), SHOP_B, INSIDE_WINDOW);

            assertThat(jobTimes.findForReportWindow(WINDOW_START, WINDOW_END, null, List.of(), true))
                    .contains(atA, atB);
        }

        @Test
        @DisplayName("a supplied location filter narrows to that location")
        void suppliedLocationNarrows() {
            ExtJobTimeReplica atA = jobTime(UUID.randomUUID(), SHOP_A, INSIDE_WINDOW);
            ExtJobTimeReplica atB = jobTime(UUID.randomUUID(), SHOP_B, INSIDE_WINDOW);

            assertThat(jobTimes.findForReportWindow(WINDOW_START, WINDOW_END, SHOP_A, List.of(), true))
                    .contains(atA)
                    .doesNotContain(atB);
        }

        @Test
        @DisplayName("a row with no location is excluded, because the report keys on one")
        void rowWithoutLocationIsExcluded() {
            ExtJobTimeReplica noLocation = jobTime(UUID.randomUUID(), null, INSIDE_WINDOW);

            assertThat(jobTimes.findForReportWindow(WINDOW_START, WINDOW_END, null, List.of(), true))
                    .doesNotContain(noLocation);
        }

        @Test
        @DisplayName("naming technicians narrows to them")
        void namedTechniciansNarrow() {
            UUID alice = UUID.randomUUID();
            ExtJobTimeReplica aliceRow = jobTime(alice, SHOP_A, INSIDE_WINDOW);
            ExtJobTimeReplica bobRow = jobTime(UUID.randomUUID(), SHOP_A, INSIDE_WINDOW);

            assertThat(jobTimes.findForReportWindow(WINDOW_START, WINDOW_END, SHOP_A, List.of(alice), false))
                    .contains(aliceRow)
                    .doesNotContain(bobRow);
        }

        @Test
        @DisplayName("the within-locations variant reads only the caller's reach")
        void withinLocationsReadsOnlyTheReach() {
            ExtJobTimeReplica atA = jobTime(UUID.randomUUID(), SHOP_A, INSIDE_WINDOW);
            ExtJobTimeReplica atB = jobTime(UUID.randomUUID(), SHOP_B, INSIDE_WINDOW);

            assertThat(jobTimes.findForReportWindowWithinLocations(
                            WINDOW_START, WINDOW_END, List.of(SHOP_A), List.of(), true))
                    .contains(atA)
                    .doesNotContain(atB);
        }
    }

    @Nested
    @DisplayName("findForApprovalQueueWithinLocations — the location-scoped approvals queue")
    class ApprovalQueueWithinLocations {

        @Test
        @DisplayName("every optional filter is applied, absent and supplied alike")
        void optionalFiltersApply() {
            UUID alice = UUID.randomUUID();
            UUID bob = UUID.randomUUID();
            TimeEntry alicePending = attendance(alice, SHOP_A, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);
            TimeEntry bobPending = attendance(bob, SHOP_A, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);
            TimeEntry aliceApproved = attendance(alice, SHOP_A, INSIDE_WINDOW, TimeEntryStatus.APPROVED);

            assertThat(timeEntries
                            .findForApprovalQueueWithinLocations(
                                    null, null, List.of(SHOP_A), WINDOW_START, WINDOW_END, PageRequest.of(0, 20))
                            .getContent())
                    .as("no filter is every entry in reach")
                    .contains(alicePending, bobPending, aliceApproved);
            assertThat(timeEntries
                            .findForApprovalQueueWithinLocations(
                                    TimeEntryStatus.PENDING_APPROVAL,
                                    null,
                                    List.of(SHOP_A),
                                    WINDOW_START,
                                    WINDOW_END,
                                    PageRequest.of(0, 20))
                            .getContent())
                    .as("the status filter narrows to what an approver can act on")
                    .contains(alicePending, bobPending)
                    .doesNotContain(aliceApproved);
            assertThat(timeEntries
                            .findForApprovalQueueWithinLocations(
                                    null, alice, List.of(SHOP_A), WINDOW_START, WINDOW_END, PageRequest.of(0, 20))
                            .getContent())
                    .as("the person filter narrows to one employee")
                    .contains(alicePending, aliceApproved)
                    .doesNotContain(bobPending);
        }

        @Test
        @DisplayName("a location outside the reach contributes nothing")
        void locationOutsideTheReachIsExcluded() {
            TimeEntry atB = attendance(UUID.randomUUID(), SHOP_B, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);

            assertThat(timeEntries
                            .findForApprovalQueueWithinLocations(
                                    null, null, List.of(SHOP_A), WINDOW_START, WINDOW_END, PageRequest.of(0, 20))
                            .getContent())
                    .doesNotContain(atB);
        }

        @Test
        @DisplayName("an unpaged request returns every match rather than failing")
        void unpagedRequestReturnsEveryMatch() {
            TimeEntry pending = attendance(UUID.randomUUID(), SHOP_A, INSIDE_WINDOW, TimeEntryStatus.PENDING_APPROVAL);

            // Pageable.unpaged() reports a page size of zero, which PageRequest.of rejects; the
            // count query has to carry the unpaged case through rather than throw.
            assertThat(timeEntries
                            .findForApprovalQueueWithinLocations(
                                    null, null, List.of(SHOP_A), WINDOW_START, WINDOW_END, Pageable.unpaged())
                            .getContent())
                    .contains(pending);
        }
    }
}
