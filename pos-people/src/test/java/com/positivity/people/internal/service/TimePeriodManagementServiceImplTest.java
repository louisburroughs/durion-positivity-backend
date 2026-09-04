package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.config.TimePeriodProperties;
import com.positivity.people.internal.dto.CreateTimePeriodRequest;
import com.positivity.people.internal.dto.TimePeriodDto;
import com.positivity.people.internal.dto.TimePeriodRolloverResult;
import com.positivity.people.internal.entity.TimePeriod;
import com.positivity.people.internal.enums.TimePeriodStatus;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.repository.TimePeriodRepository;
import com.positivity.people.internal.repository.TimekeepingEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/** Pay-period creation, status transitions, and the scheduled rollover pass (#1527). */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimePeriodManagementServiceImpl")
class TimePeriodManagementServiceImplTest {

    private static final UUID TENANT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c01");
    private static final UUID PERIOD_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4c02");

    // "Today" for every test: 2026-06-10, inside the anchor period 2026-06-01..2026-06-14.
    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-06-10T12:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 6, 10);
    private static final LocalDate ANCHOR = LocalDate.of(2026, 6, 1);

    @Mock
    private TimePeriodRepository timePeriodRepository;

    @Mock
    private TimekeepingEntryRepository timekeepingEntryRepository;

    private TimePeriodProperties properties;

    private TimePeriodManagementServiceImpl service;

    @BeforeEach
    void setUp() {
        properties = new TimePeriodProperties();
        service = new TimePeriodManagementServiceImpl(
                timePeriodRepository, timekeepingEntryRepository, properties, FIXED_CLOCK);
    }

    private TimePeriod period(TimePeriodStatus status, LocalDate start, LocalDate end) {
        TimePeriod p = new TimePeriod();
        p.setTimePeriodId(PERIOD_ID);
        p.setTenantId(TENANT_ID);
        p.setStartDate(start);
        p.setEndDate(end);
        p.setStatus(status);
        return p;
    }

    @Nested
    @DisplayName("createTimePeriod")
    class CreateTimePeriod {

        private CreateTimePeriodRequest request(LocalDate start, LocalDate end) {
            return CreateTimePeriodRequest.builder()
                    .tenantId(TENANT_ID)
                    .startDate(start)
                    .endDate(end)
                    .build();
        }

        @Test
        @DisplayName("saves an OPEN period when no status is given and no overlap exists")
        void createsOpenPeriod() {
            when(timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            TENANT_ID, LocalDate.of(2026, 6, 14), ANCHOR))
                    .thenReturn(false);
            when(timePeriodRepository.saveAndFlush(any(TimePeriod.class))).thenAnswer(inv -> inv.getArgument(0));

            TimePeriodDto dto = service.createTimePeriod(request(ANCHOR, LocalDate.of(2026, 6, 14)));

            assertThat(dto.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(dto.getStartDate()).isEqualTo(ANCHOR);
            assertThat(dto.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 14));
            assertThat(dto.getStatus()).isEqualTo(TimePeriodStatus.OPEN);
        }

        @Test
        @DisplayName("keeps an explicitly requested status")
        void keepsExplicitStatus() {
            when(timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            any(), any(), any()))
                    .thenReturn(false);
            when(timePeriodRepository.saveAndFlush(any(TimePeriod.class))).thenAnswer(inv -> inv.getArgument(0));

            CreateTimePeriodRequest req = CreateTimePeriodRequest.builder()
                    .tenantId(TENANT_ID)
                    .startDate(LocalDate.of(2026, 5, 18))
                    .endDate(LocalDate.of(2026, 5, 31))
                    .status(TimePeriodStatus.PAYROLL_CLOSED)
                    .build();

            assertThat(service.createTimePeriod(req).getStatus()).isEqualTo(TimePeriodStatus.PAYROLL_CLOSED);
        }

        @Test
        @DisplayName("rejects endDate before startDate with RequestValidationException (400)")
        void rejectsInvertedRange() {
            assertThatThrownBy(() -> service.createTimePeriod(request(LocalDate.of(2026, 6, 14), ANCHOR)))
                    .isInstanceOf(RequestValidationException.class)
                    .hasMessageContaining("endDate");
            verify(timePeriodRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("rejects an overlapping range with IllegalStateException (409)")
        void rejectsOverlap() {
            when(timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            any(), any(), any()))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.createTimePeriod(request(ANCHOR, LocalDate.of(2026, 6, 14))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("overlaps");
            verify(timePeriodRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("maps a unique-constraint race to IllegalStateException (409)")
        void mapsConstraintRaceToConflict() {
            when(timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            any(), any(), any()))
                    .thenReturn(false);
            when(timePeriodRepository.saveAndFlush(any(TimePeriod.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate"));

            assertThatThrownBy(() -> service.createTimePeriod(request(ANCHOR, LocalDate.of(2026, 6, 14))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("already exists");
        }
    }

    @Nested
    @DisplayName("transitionTimePeriod")
    class TransitionTimePeriod {

        @Test
        @DisplayName("moves OPEN to SUBMISSION_CLOSED")
        void closesSubmissions() {
            TimePeriod open = period(TimePeriodStatus.OPEN, ANCHOR, LocalDate.of(2026, 6, 14));
            when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(open));
            when(timePeriodRepository.save(any(TimePeriod.class))).thenAnswer(inv -> inv.getArgument(0));

            TimePeriodDto dto = service.transitionTimePeriod(PERIOD_ID, TimePeriodStatus.SUBMISSION_CLOSED);

            assertThat(dto.getStatus()).isEqualTo(TimePeriodStatus.SUBMISSION_CLOSED);
        }

        @Test
        @DisplayName("reopens a SUBMISSION_CLOSED period for corrections")
        void reopensForCorrections() {
            TimePeriod closed = period(TimePeriodStatus.SUBMISSION_CLOSED, ANCHOR, LocalDate.of(2026, 6, 14));
            when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(closed));
            when(timePeriodRepository.save(any(TimePeriod.class))).thenAnswer(inv -> inv.getArgument(0));

            assertThat(service.transitionTimePeriod(PERIOD_ID, TimePeriodStatus.OPEN)
                            .getStatus())
                    .isEqualTo(TimePeriodStatus.OPEN);
        }

        @Test
        @DisplayName("refuses to leave PAYROLL_CLOSED (terminal)")
        void payrollClosedIsTerminal() {
            TimePeriod closed = period(TimePeriodStatus.PAYROLL_CLOSED, ANCHOR, LocalDate.of(2026, 6, 14));
            when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(closed));

            assertThatThrownBy(() -> service.transitionTimePeriod(PERIOD_ID, TimePeriodStatus.OPEN))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("not allowed");
            verify(timePeriodRepository, never()).save(any());
        }

        @Test
        @DisplayName("refuses a no-op transition to the current status")
        void refusesNoOpTransition() {
            TimePeriod open = period(TimePeriodStatus.OPEN, ANCHOR, LocalDate.of(2026, 6, 14));
            when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(open));

            assertThatThrownBy(() -> service.transitionTimePeriod(PERIOD_ID, TimePeriodStatus.OPEN))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        @DisplayName("throws EntityNotFoundException (404) for an unknown period")
        void unknownPeriod() {
            when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.transitionTimePeriod(PERIOD_ID, TimePeriodStatus.SUBMISSION_CLOSED))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("runRollover")
    class RunRollover {

        @BeforeEach
        void quietDefaults() {
            // lenient: individual tests override these defaults, which would otherwise trip
            // strict-stubs as unnecessary stubbings.
            lenient()
                    .when(timePeriodRepository.findByStatusAndEndDateBefore(any(), any()))
                    .thenReturn(List.of());
            lenient().when(timekeepingEntryRepository.findDistinctTenantIds()).thenReturn(List.of());
            lenient().when(timePeriodRepository.findDistinctTenantIds()).thenReturn(List.of());
        }

        @Test
        @DisplayName("advances ended OPEN periods to SUBMISSION_CLOSED using the submission grace")
        void closesSubmissionsAfterPeriodEnd() {
            TimePeriod ended = period(TimePeriodStatus.OPEN, LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 31));
            when(timePeriodRepository.findByStatusAndEndDateBefore(TimePeriodStatus.OPEN, TODAY))
                    .thenReturn(List.of(ended));

            TimePeriodRolloverResult result = service.runRollover();

            assertThat(result.getSubmissionsClosed()).isEqualTo(1);
            assertThat(ended.getStatus()).isEqualTo(TimePeriodStatus.SUBMISSION_CLOSED);
        }

        @Test
        @DisplayName("advances SUBMISSION_CLOSED periods to PAYROLL_CLOSED after the payroll grace")
        void closesPayrollAfterGrace() {
            TimePeriod reviewed =
                    period(TimePeriodStatus.SUBMISSION_CLOSED, LocalDate.of(2026, 5, 18), LocalDate.of(2026, 5, 31));
            when(timePeriodRepository.findByStatusAndEndDateBefore(
                            TimePeriodStatus.SUBMISSION_CLOSED, TODAY.minusDays(7)))
                    .thenReturn(List.of(reviewed));

            TimePeriodRolloverResult result = service.runRollover();

            assertThat(result.getPayrollsClosed()).isEqualTo(1);
            assertThat(reviewed.getStatus()).isEqualTo(TimePeriodStatus.PAYROLL_CLOSED);
        }

        @Test
        @DisplayName("creates the current grid period for a tenant with entries and no periods")
        void createsCurrentGridPeriod() {
            when(timekeepingEntryRepository.findDistinctTenantIds()).thenReturn(List.of(TENANT_ID));
            when(timekeepingEntryRepository.findEarliestSessionStartByTenantId(TENANT_ID))
                    .thenReturn(Instant.parse("2026-06-02T08:00:00Z"));
            when(timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            any(), any(), any()))
                    .thenReturn(false);
            when(timePeriodRepository.saveAndFlush(any(TimePeriod.class))).thenAnswer(inv -> inv.getArgument(0));

            TimePeriodRolloverResult result = service.runRollover();

            assertThat(result.getPeriodsCreated()).isEqualTo(1);
            ArgumentCaptor<TimePeriod> captor = ArgumentCaptor.forClass(TimePeriod.class);
            verify(timePeriodRepository).saveAndFlush(captor.capture());
            TimePeriod created = captor.getValue();
            assertThat(created.getTenantId()).isEqualTo(TENANT_ID);
            assertThat(created.getStartDate()).isEqualTo(ANCHOR);
            assertThat(created.getEndDate()).isEqualTo(LocalDate.of(2026, 6, 14));
            assertThat(created.getStatus()).isEqualTo(TimePeriodStatus.OPEN);
        }

        @Test
        @DisplayName("backfills grid periods from the tenant's earliest entry")
        void backfillsFromEarliestEntry() {
            when(timekeepingEntryRepository.findDistinctTenantIds()).thenReturn(List.of(TENANT_ID));
            // Earliest entry two grid periods back: 2026-05-04..2026-05-17 and 2026-05-18..2026-05-31
            // precede the current 2026-06-01..2026-06-14 window.
            when(timekeepingEntryRepository.findEarliestSessionStartByTenantId(TENANT_ID))
                    .thenReturn(Instant.parse("2026-05-05T08:00:00Z"));
            when(timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            any(), any(), any()))
                    .thenReturn(false);
            when(timePeriodRepository.saveAndFlush(any(TimePeriod.class))).thenAnswer(inv -> inv.getArgument(0));

            TimePeriodRolloverResult result = service.runRollover();

            assertThat(result.getPeriodsCreated()).isEqualTo(3);
            ArgumentCaptor<TimePeriod> captor = ArgumentCaptor.forClass(TimePeriod.class);
            verify(timePeriodRepository, times(3)).saveAndFlush(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(TimePeriod::getStartDate)
                    .containsExactly(LocalDate.of(2026, 5, 4), LocalDate.of(2026, 5, 18), ANCHOR);
        }

        @Test
        @DisplayName("skips grid windows already covered by an existing period")
        void skipsCoveredWindows() {
            when(timekeepingEntryRepository.findDistinctTenantIds()).thenReturn(List.of(TENANT_ID));
            when(timekeepingEntryRepository.findEarliestSessionStartByTenantId(TENANT_ID))
                    .thenReturn(Instant.parse("2026-06-02T08:00:00Z"));
            when(timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            TENANT_ID, LocalDate.of(2026, 6, 14), ANCHOR))
                    .thenReturn(true);

            TimePeriodRolloverResult result = service.runRollover();

            assertThat(result.getPeriodsCreated()).isZero();
            verify(timePeriodRepository, never()).saveAndFlush(any());
        }

        @Test
        @DisplayName("caps backfill at maxBackfillPeriods")
        void capsBackfill() {
            properties.setMaxBackfillPeriods(2);
            when(timekeepingEntryRepository.findDistinctTenantIds()).thenReturn(List.of(TENANT_ID));
            // Entry a year back; only the 2 most recent grid windows may be created.
            when(timekeepingEntryRepository.findEarliestSessionStartByTenantId(TENANT_ID))
                    .thenReturn(Instant.parse("2025-06-01T08:00:00Z"));
            when(timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            any(), any(), any()))
                    .thenReturn(false);
            when(timePeriodRepository.saveAndFlush(any(TimePeriod.class))).thenAnswer(inv -> inv.getArgument(0));

            TimePeriodRolloverResult result = service.runRollover();

            assertThat(result.getPeriodsCreated()).isEqualTo(2);
            ArgumentCaptor<TimePeriod> captor = ArgumentCaptor.forClass(TimePeriod.class);
            verify(timePeriodRepository, times(2)).saveAndFlush(captor.capture());
            assertThat(captor.getAllValues())
                    .extracting(TimePeriod::getStartDate)
                    .containsExactly(LocalDate.of(2026, 5, 18), ANCHOR);
        }

        @Test
        @DisplayName("covers tenants known only from existing periods, ignoring entry-less coverage before today")
        void coversPeriodOnlyTenants() {
            when(timePeriodRepository.findDistinctTenantIds()).thenReturn(List.of(TENANT_ID));
            when(timekeepingEntryRepository.findEarliestSessionStartByTenantId(TENANT_ID))
                    .thenReturn(null);
            when(timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            any(), any(), any()))
                    .thenReturn(false);
            when(timePeriodRepository.saveAndFlush(any(TimePeriod.class))).thenAnswer(inv -> inv.getArgument(0));

            TimePeriodRolloverResult result = service.runRollover();

            assertThat(result.getPeriodsCreated()).isEqualTo(1);
        }

        @Test
        @DisplayName("fails fast on a non-positive period length instead of looping forever")
        void rejectsNonPositivePeriodLength() {
            properties.setPeriodLengthDays(0);

            assertThatThrownBy(() -> service.runRollover())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("period-length-days");
        }

        @Test
        @DisplayName("fails fast on a max-backfill-periods below 1")
        void rejectsInvalidBackfillCap() {
            properties.setMaxBackfillPeriods(0);

            assertThatThrownBy(() -> service.runRollover())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("max-backfill-periods");
        }

        @Test
        @DisplayName("swallows a concurrent-create constraint violation and keeps going")
        void toleratesConcurrentCreate() {
            when(timekeepingEntryRepository.findDistinctTenantIds()).thenReturn(List.of(TENANT_ID));
            when(timekeepingEntryRepository.findEarliestSessionStartByTenantId(TENANT_ID))
                    .thenReturn(Instant.parse("2026-06-02T08:00:00Z"));
            when(timePeriodRepository.existsByTenantIdAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
                            any(), any(), any()))
                    .thenReturn(false);
            when(timePeriodRepository.saveAndFlush(any(TimePeriod.class)))
                    .thenThrow(new DataIntegrityViolationException("duplicate"));

            TimePeriodRolloverResult result = service.runRollover();

            assertThat(result.getPeriodsCreated()).isZero();
        }
    }
}
