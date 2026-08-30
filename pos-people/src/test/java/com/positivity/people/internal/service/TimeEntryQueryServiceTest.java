package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.dto.TimeEntrySummary;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.enums.TimeEntryStatus;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.repository.TimeEntryAuditRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * The read side of {@link TimeEntryServiceImpl} (#1573): the approvals queue and the single-entry
 * detail the CAP_139.130 screen reads.
 *
 * <p>What is worth asserting here is not that the repository is called — it is how the request's
 * calendar day becomes an instant window, and how the entity's two decision pairs become the one
 * decision the screen shows. Both are choices a caller cannot see and a refactor could silently
 * change.
 */
@DisplayName("TimeEntryServiceImpl — the attendance approvals queue")
class TimeEntryQueryServiceTest {

    private static final UUID ENTRY_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b");

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5c");

    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5d");

    private TimeEntryRepository repository;

    private TimeEntryService service;

    @BeforeEach
    void setUp() {
        repository = mock(TimeEntryRepository.class);
        service = new TimeEntryServiceImpl(Clock.systemUTC(), repository, mock(TimeEntryAuditRepository.class));
    }

    private TimeEntry pendingEntry() {
        TimeEntry entry = new TimeEntry();
        entry.setTimeEntryId(ENTRY_ID);
        entry.setPersonId(PERSON_ID);
        entry.setLocationId(LOCATION_ID);
        entry.setAttendanceStartAt(Instant.parse("2026-01-15T13:00:00Z"));
        entry.setAttendanceEndAt(Instant.parse("2026-01-15T21:30:00Z"));
        entry.setBreakMinutes(30);
        entry.setStatus(TimeEntryStatus.PENDING_APPROVAL);
        entry.setSubmittedAt(Instant.parse("2026-01-15T21:35:00Z"));
        return entry;
    }

    private void stubPageOf(TimeEntry... entries) {
        when(repository.findForApprovalQueue(any(), any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    Pageable pageable = invocation.getArgument(5);
                    return new PageImpl<>(List.of(entries), pageable, entries.length);
                });
    }

    @Test
    @DisplayName("a workDate becomes the half-open instant window of that day in the requested zone")
    void workDateBecomesADayWindowInTheRequestedZone() {
        stubPageOf(pendingEntry());

        service.listTimeEntries(
                TimeEntryStatus.PENDING_APPROVAL,
                LocalDate.of(2026, 1, 15),
                ZoneId.of("America/Chicago"),
                null,
                null,
                0,
                20);

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(repository)
                .findForApprovalQueue(
                        eq(TimeEntryStatus.PENDING_APPROVAL),
                        eq(null),
                        eq(null),
                        start.capture(),
                        end.capture(),
                        eq(PageRequest.of(0, 20)));

        // Chicago is UTC-6 on 15 January, so the local day starts at 06:00Z and runs 24 hours.
        assertThat(start.getValue()).isEqualTo(Instant.parse("2026-01-15T06:00:00Z"));
        assertThat(end.getValue()).isEqualTo(Instant.parse("2026-01-16T06:00:00Z"));
    }

    @Test
    @DisplayName("no workDate widens the window instead of nulling it, so every day matches")
    void absentWorkDateWidensTheWindow() {
        stubPageOf(pendingEntry());

        service.listTimeEntries(null, null, null, null, null, 0, 20);

        ArgumentCaptor<Instant> start = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> end = ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(repository)
                .findForApprovalQueue(
                        eq(null), eq(null), eq(null), start.capture(), end.capture(), any(Pageable.class));

        assertThat(start.getValue()).isBefore(Instant.parse("1900-01-01T00:00:00Z"));
        assertThat(end.getValue()).isAfter(Instant.parse("2200-01-01T00:00:00Z"));
    }

    @Test
    @DisplayName("an entry's workDate is derived in the zone the caller asked for, not in UTC")
    void workDateIsDerivedInTheCallersZone() {
        TimeEntry lateShift = pendingEntry();
        // 21:00 in Chicago on 15 January is already the 16th in UTC.
        lateShift.setAttendanceStartAt(Instant.parse("2026-01-16T03:00:00Z"));
        stubPageOf(lateShift);

        PagedResponse<TimeEntrySummary> page = service.listTimeEntries(
                null, LocalDate.of(2026, 1, 15), ZoneId.of("America/Chicago"), null, null, 0, 20);

        assertThat(page.items()).singleElement().satisfies(item -> {
            assertThat(item.workDate()).isEqualTo(LocalDate.of(2026, 1, 15));
            assertThat(item.startAtUtc()).isEqualTo(Instant.parse("2026-01-16T03:00:00Z"));
        });
    }

    @Test
    @DisplayName("attendance is reported as clock-in, clock-out and break minutes, with no workorder")
    void reportsAttendanceFields() {
        stubPageOf(pendingEntry());

        TimeEntrySummary item = service.listTimeEntries(null, null, ZoneOffset.UTC, null, null, 0, 20)
                .items()
                .getFirst();

        assertThat(item.timeEntryId()).isEqualTo(ENTRY_ID);
        assertThat(item.employeeId()).isEqualTo(PERSON_ID);
        assertThat(item.locationId()).isEqualTo(LOCATION_ID);
        assertThat(item.startAtUtc()).isEqualTo(Instant.parse("2026-01-15T13:00:00Z"));
        assertThat(item.endAtUtc()).isEqualTo(Instant.parse("2026-01-15T21:30:00Z"));
        assertThat(item.breakMinutes()).isEqualTo(30);
        assertThat(item.submittedAtUtc()).isEqualTo(Instant.parse("2026-01-15T21:35:00Z"));
    }

    @Test
    @DisplayName("a pending entry carries no decision")
    void pendingEntryHasNoDecision() {
        stubPageOf(pendingEntry());

        TimeEntrySummary item = service.listTimeEntries(null, null, null, null, null, 0, 20)
                .items()
                .getFirst();

        assertThat(item.decisionByUserId()).isNull();
        assertThat(item.decisionAtUtc()).isNull();
        assertThat(item.rejectionReason()).isNull();
    }

    @Test
    @DisplayName("an approved entry's decision comes from the approved pair")
    void approvedEntryReportsTheApprover() {
        TimeEntry approved = pendingEntry();
        approved.setStatus(TimeEntryStatus.APPROVED);
        approved.setApprovedBy("manager1");
        approved.setApprovedAt(Instant.parse("2026-01-16T09:00:00Z"));
        stubPageOf(approved);

        TimeEntrySummary item = service.listTimeEntries(null, null, null, null, null, 0, 20)
                .items()
                .getFirst();

        assertThat(item.decisionByUserId()).isEqualTo("manager1");
        assertThat(item.decisionAtUtc()).isEqualTo(Instant.parse("2026-01-16T09:00:00Z"));
    }

    @Test
    @DisplayName("a rejected entry's decision comes from the rejected pair, not the approved one")
    void rejectedEntryReportsTheRejector() {
        TimeEntry rejected = pendingEntry();
        rejected.setStatus(TimeEntryStatus.REJECTED);
        rejected.setRejectedBy("manager2");
        rejected.setRejectedAt(Instant.parse("2026-01-16T10:00:00Z"));
        rejected.setRejectionReason("Missing lunch break");
        stubPageOf(rejected);

        TimeEntrySummary item = service.listTimeEntries(null, null, null, null, null, 0, 20)
                .items()
                .getFirst();

        assertThat(item.decisionByUserId()).isEqualTo("manager2");
        assertThat(item.decisionAtUtc()).isEqualTo(Instant.parse("2026-01-16T10:00:00Z"));
        assertThat(item.rejectionReason()).isEqualTo("Missing lunch break");
    }

    @Test
    @DisplayName("the page envelope reports the requested page and the repository's totals")
    void pageEnvelopeCarriesTotals() {
        Page<TimeEntry> secondPage = new PageImpl<>(List.of(pendingEntry()), PageRequest.of(1, 2), 5);
        when(repository.findForApprovalQueue(any(), any(), any(), any(), any(), any()))
                .thenReturn(secondPage);

        PagedResponse<TimeEntrySummary> page = service.listTimeEntries(null, null, null, null, null, 1, 2);

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(2);
        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
    }

    @Test
    @DisplayName("a missing entry is a 404, not an empty body")
    void missingEntryIsNotFound() {
        when(repository.findById(ENTRY_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTimeEntry(ENTRY_ID, ZoneOffset.UTC))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(ENTRY_ID.toString());
    }

    @Test
    @DisplayName("the detail view returns the same shape as a queue row")
    void detailReturnsTheSameShape() {
        when(repository.findById(ENTRY_ID)).thenReturn(Optional.of(pendingEntry()));

        TimeEntrySummary item = service.getTimeEntry(ENTRY_ID, ZoneOffset.UTC);

        assertThat(item.timeEntryId()).isEqualTo(ENTRY_ID);
        assertThat(item.status()).isEqualTo(TimeEntryStatus.PENDING_APPROVAL);
        assertThat(item.workDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }
}
