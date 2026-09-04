package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.AttendanceDiscrepancyReportResponse;
import com.positivity.people.internal.dto.AttendanceReportKey;
import com.positivity.people.internal.entity.ExtJobTimeReplica;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.entity.TimeEntry;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.repository.ExtJobTimeReplicaRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.TimeEntryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Attendance-versus-job-time discrepancy report (ADR-0044 §6, #875): attendance minutes are
 * bucketed into local dates, job minutes come from the {@code ext_workorder_job_time} replica,
 * and a row is flagged when the gap exceeds the resolved threshold.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("PeopleReportsServiceImpl.getAttendanceDiscrepancyReport")
class AttendanceDiscrepancyReportTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d01");
    private static final UUID OTHER_PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d02");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4d03");
    private static final LocalDate DAY = LocalDate.of(2026, 3, 2);
    private static final String TIMEZONE = "UTC";
    private static final String ACTOR = "svc-reports";

    @Mock
    private TimeEntryRepository timeEntryRepository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private ExtJobTimeReplicaRepository extJobTimeReplicaRepository;

    @Mock
    private LocationReferenceService locationReferenceService;

    @Mock
    private TimekeepingThresholdCache timekeepingThresholdCache;

    @Mock
    private TimekeepingThresholdCache.ThresholdResolverContext thresholdContext;

    private PeopleReportsServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new PeopleReportsServiceImpl(
                timeEntryRepository,
                extPersonReplicaRepository,
                extJobTimeReplicaRepository,
                locationReferenceService,
                timekeepingThresholdCache,
                Clock.fixed(Instant.parse("2026-03-02T20:00:00Z"), ZoneOffset.UTC));
        when(timekeepingThresholdCache.createContext(any(), any())).thenReturn(thresholdContext);
        when(thresholdContext.resolveThresholdMinutes(any(), any())).thenReturn(30);
    }

    private static TimeEntry attendance(UUID personId, Instant startAt, Instant endAt) {
        TimeEntry entry = new TimeEntry();
        entry.setTimeEntryId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4dff"));
        entry.setPersonId(personId);
        entry.setLocationId(LOCATION_ID);
        entry.setAttendanceStartAt(startAt);
        entry.setAttendanceEndAt(endAt);
        return entry;
    }

    private static ExtJobTimeReplica jobTime(UUID technicianId, Instant endAtUtc, int minutes) {
        ExtJobTimeReplica row = new ExtJobTimeReplica();
        row.setLaborEntryId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4dfe"));
        row.setTechnicianId(technicianId);
        row.setLocationId(LOCATION_ID);
        row.setEndAtUtc(endAtUtc);
        row.setMinutes(minutes);
        return row;
    }

    private void stubAttendance(List<TimeEntry> entries) {
        when(timeEntryRepository.findAttendanceOverlappingWindow(any(), any(), any(), anyList(), anyBoolean()))
                .thenReturn(entries);
    }

    private void stubJobTime(List<ExtJobTimeReplica> rows) {
        when(extJobTimeReplicaRepository.findForReportWindow(any(), any(), any(), anyList(), anyBoolean()))
                .thenReturn(rows);
    }

    private List<AttendanceDiscrepancyReportResponse> report(boolean flaggedOnly) {
        return service.getAttendanceDiscrepancyReport(
                DAY, DAY, TIMEZONE, LOCATION_ID, List.of(), flaggedOnly, ACTOR, "corr-1");
    }

    @Test
    void reportsTheGapBetweenAttendanceAndJobMinutes() {
        stubAttendance(List.of(
                attendance(PERSON_ID, Instant.parse("2026-03-02T13:00:00Z"), Instant.parse("2026-03-02T21:00:00Z"))));
        stubJobTime(List.of(jobTime(PERSON_ID, Instant.parse("2026-03-02T20:00:00Z"), 300)));
        ExtPersonReplica person = new ExtPersonReplica();
        person.setPersonId(PERSON_ID);
        person.setFirstName("Jane");
        person.setLastName("Smith");
        when(extPersonReplicaRepository.findAllById(Set.of(PERSON_ID))).thenReturn(List.of(person));

        List<AttendanceDiscrepancyReportResponse> rows = report(false);

        assertThat(rows).hasSize(1);
        AttendanceDiscrepancyReportResponse row = rows.get(0);
        assertThat(row.getTechnicianId()).isEqualTo(PERSON_ID.toString());
        assertThat(row.getTechnicianName()).isEqualTo("Jane Smith");
        assertThat(row.getReportDate()).isEqualTo(DAY);
        assertThat(row.getTotalAttendanceHours()).isEqualTo(8.0d);
        assertThat(row.getTotalJobHours()).isEqualTo(5.0d);
        assertThat(row.getDiscrepancyHours()).isEqualTo(3.0d);
        assertThat(row.getThresholdApplied()).isEqualTo(30);
        assertThat(row.isFlagged()).isTrue();
    }

    @Test
    void leavesARowUnflaggedWhenTheGapIsInsideTheThreshold() {
        stubAttendance(List.of(
                attendance(PERSON_ID, Instant.parse("2026-03-02T13:00:00Z"), Instant.parse("2026-03-02T21:00:00Z"))));
        stubJobTime(List.of(jobTime(PERSON_ID, Instant.parse("2026-03-02T20:00:00Z"), 470)));

        assertThat(report(false).get(0).isFlagged()).isFalse();
    }

    @Test
    void dropsUnflaggedRowsWhenOnlyFlaggedOnesWereAskedFor() {
        stubAttendance(List.of(
                attendance(PERSON_ID, Instant.parse("2026-03-02T13:00:00Z"), Instant.parse("2026-03-02T21:00:00Z"))));
        stubJobTime(List.of(jobTime(PERSON_ID, Instant.parse("2026-03-02T20:00:00Z"), 470)));

        assertThat(report(true)).isEmpty();
    }

    @Test
    void fallsBackToTheTechnicianIdWhenTheReplicaHasNoName() {
        stubAttendance(List.of(
                attendance(PERSON_ID, Instant.parse("2026-03-02T13:00:00Z"), Instant.parse("2026-03-02T21:00:00Z"))));
        stubJobTime(List.of());
        ExtPersonReplica nameless = new ExtPersonReplica();
        nameless.setPersonId(PERSON_ID);
        when(extPersonReplicaRepository.findAllById(any(Iterable.class))).thenReturn(List.of(nameless));

        assertThat(report(false).get(0).getTechnicianName()).isEqualTo(PERSON_ID.toString());
    }

    @Test
    void keepsAJobTimeOnlyTechnicianWithZeroAttendance() {
        stubAttendance(List.of());
        stubJobTime(List.of(jobTime(OTHER_PERSON_ID, Instant.parse("2026-03-02T20:00:00Z"), 120)));

        List<AttendanceDiscrepancyReportResponse> rows = report(false);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getTotalAttendanceHours()).isZero();
        assertThat(rows.get(0).getTotalJobHours()).isEqualTo(2.0d);
        assertThat(rows.get(0).getDiscrepancyHours()).isEqualTo(-2.0d);
        assertThat(rows.get(0).isFlagged()).isTrue();
    }

    @Test
    void splitsAnOvernightShiftAcrossTheTwoLocalDatesItCovers() {
        stubAttendance(List.of(
                attendance(PERSON_ID, Instant.parse("2026-03-02T22:00:00Z"), Instant.parse("2026-03-03T06:00:00Z"))));
        stubJobTime(List.of());

        List<AttendanceDiscrepancyReportResponse> rows = service.getAttendanceDiscrepancyReport(
                DAY, DAY.plusDays(1), TIMEZONE, LOCATION_ID, List.of(), false, ACTOR, null);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getReportDate()).isEqualTo(DAY);
        assertThat(rows.get(0).getTotalAttendanceHours()).isEqualTo(2.0d);
        assertThat(rows.get(1).getReportDate()).isEqualTo(DAY.plusDays(1));
        assertThat(rows.get(1).getTotalAttendanceHours()).isEqualTo(6.0d);
    }

    @Test
    void clipsAttendanceToTheRequestedWindow() {
        // The shift starts the day before the window and ends inside it.
        stubAttendance(List.of(
                attendance(PERSON_ID, Instant.parse("2026-03-01T20:00:00Z"), Instant.parse("2026-03-02T04:00:00Z"))));
        stubJobTime(List.of());

        assertThat(report(false).get(0).getTotalAttendanceHours()).isEqualTo(4.0d);
    }

    @Test
    void treatsAnOpenShiftAsRunningUntilNow() {
        stubAttendance(List.of(attendance(PERSON_ID, Instant.parse("2026-03-02T13:00:00Z"), null)));
        stubJobTime(List.of());

        // The clock is fixed at 20:00Z, so the open shift counts seven hours, not the whole day.
        assertThat(report(false).get(0).getTotalAttendanceHours()).isEqualTo(7.0d);
    }

    @Test
    void ignoresAnAttendanceRowMissingTheDimensionsItWouldBeBucketedBy() {
        TimeEntry noPerson =
                attendance(null, Instant.parse("2026-03-02T13:00:00Z"), Instant.parse("2026-03-02T21:00:00Z"));
        TimeEntry noLocation =
                attendance(PERSON_ID, Instant.parse("2026-03-02T13:00:00Z"), Instant.parse("2026-03-02T21:00:00Z"));
        noLocation.setLocationId(null);
        TimeEntry noStart = attendance(PERSON_ID, null, Instant.parse("2026-03-02T21:00:00Z"));
        stubAttendance(List.of(noPerson, noLocation, noStart));
        stubJobTime(List.of());

        assertThat(report(false)).isEmpty();
    }

    @Test
    void ignoresAnAttendanceRowThatEndsBeforeItStarts() {
        stubAttendance(List.of(
                attendance(PERSON_ID, Instant.parse("2026-03-02T21:00:00Z"), Instant.parse("2026-03-02T13:00:00Z"))));
        stubJobTime(List.of());

        assertThat(report(false)).isEmpty();
    }

    @Test
    void ignoresJobTimeWhoseLocalDateFallsOutsideTheRequestedRange() {
        stubAttendance(List.of());
        stubJobTime(List.of(
                jobTime(PERSON_ID, Instant.parse("2026-03-01T20:00:00Z"), 60),
                jobTime(PERSON_ID, Instant.parse("2026-03-04T20:00:00Z"), 60)));

        assertThat(report(false)).isEmpty();
    }

    @Test
    void sumsRepeatedJobTimeRowsForTheSameTechnicianAndDay() {
        stubAttendance(List.of());
        stubJobTime(List.of(
                jobTime(PERSON_ID, Instant.parse("2026-03-02T14:00:00Z"), 60),
                jobTime(PERSON_ID, Instant.parse("2026-03-02T18:00:00Z"), 90)));

        assertThat(report(false).get(0).getTotalJobHours()).isEqualTo(2.5d);
    }

    @Test
    void returnsNothingWhenNeitherSourceHasRowsInTheWindow() {
        stubAttendance(List.of());
        stubJobTime(List.of());

        assertThat(report(false)).isEmpty();
    }

    @Test
    void bucketsAttendanceByTheRequestedTimezoneRatherThanUtc() {
        // 03:00Z on 3 March is still 2 March in New York, so the minutes land on the 2nd.
        stubAttendance(List.of(
                attendance(PERSON_ID, Instant.parse("2026-03-03T01:00:00Z"), Instant.parse("2026-03-03T03:00:00Z"))));
        stubJobTime(List.of());

        List<AttendanceDiscrepancyReportResponse> rows = service.getAttendanceDiscrepancyReport(
                DAY, DAY, "America/New_York", LOCATION_ID, List.of(), false, ACTOR, null);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getReportDate()).isEqualTo(DAY);
        assertThat(rows.get(0).getTotalAttendanceHours()).isEqualTo(2.0d);
    }

    @Test
    void scopesTheReadToTheRequestedTechniciansWhenSomeAreNamed() {
        service.getAttendanceDiscrepancyReport(DAY, DAY, TIMEZONE, LOCATION_ID, List.of(PERSON_ID), false, ACTOR, null);

        org.mockito.Mockito.verify(timeEntryRepository)
                .findAttendanceOverlappingWindow(any(), any(), eq(LOCATION_ID), eq(List.of(PERSON_ID)), eq(false));
        org.mockito.Mockito.verify(extJobTimeReplicaRepository)
                .findForReportWindow(any(), any(), eq(LOCATION_ID), eq(List.of(PERSON_ID)), eq(false));
    }

    @Test
    void rejectsAnInvertedDateRange() {
        assertThatThrownBy(() -> service.getAttendanceDiscrepancyReport(
                        DAY, DAY.minusDays(1), TIMEZONE, LOCATION_ID, List.of(), false, ACTOR, null))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("endDate must be on or after startDate");
    }

    @Test
    void rejectsATimezoneThatIsNotAnIanaZone() {
        assertThatThrownBy(() -> service.getAttendanceDiscrepancyReport(
                        DAY, DAY, "Mars/Olympus", LOCATION_ID, List.of(), false, ACTOR, null))
                .isInstanceOf(RequestValidationException.class)
                .hasMessageContaining("timezone must be a valid IANA timezone");
    }

    @Test
    void survivesANonUuidTechnicianIdentifierWhenLoadingNames() {
        // A key whose technician id is not a UUID cannot be looked up; the report still renders.
        stubAttendance(List.of());
        stubJobTime(List.of());
        when(timekeepingThresholdCache.createContext(any(), any())).thenAnswer(invocation -> {
            Set<AttendanceReportKey> keys = invocation.getArgument(0);
            assertThat(keys).isNotNull();
            return thresholdContext;
        });

        assertThat(service.getAttendanceDiscrepancyReport(DAY, DAY, TIMEZONE, null, List.of(), false, "ab", null))
                .isEmpty();
    }

    @Test
    void acceptsAZoneOffsetAsWellAsARegionId() {
        stubAttendance(List.of(
                attendance(PERSON_ID, Instant.parse("2026-03-02T13:00:00Z"), Instant.parse("2026-03-02T21:00:00Z"))));
        stubJobTime(List.of());

        List<AttendanceDiscrepancyReportResponse> rows = service.getAttendanceDiscrepancyReport(
                DAY, DAY, ZoneId.of("UTC").getId(), null, List.of(), false, ACTOR, null);

        assertThat(rows).hasSize(1);
    }
}
