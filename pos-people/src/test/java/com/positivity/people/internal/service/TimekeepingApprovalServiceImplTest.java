package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.dto.ApprovalPersonDto;
import com.positivity.people.internal.dto.TimePeriodApprovalDto;
import com.positivity.people.internal.dto.TimePeriodDecisionResponse;
import com.positivity.people.internal.dto.TimePeriodDto;
import com.positivity.people.internal.dto.TimekeepingEntryDto;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.entity.TimePeriod;
import com.positivity.people.internal.entity.TimekeepingEntry;
import com.positivity.people.internal.enums.ApprovalStatus;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.TimePeriodRepository;
import com.positivity.people.internal.repository.TimekeepingEntryRepository;
import jakarta.persistence.EntityNotFoundException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Timekeeping approval reads and period-wide approve/reject decisions. */
@ExtendWith(MockitoExtension.class)
@DisplayName("TimekeepingApprovalServiceImpl")
class TimekeepingApprovalServiceImplTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01");
    private static final UUID OTHER_PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b02");
    private static final UUID PERIOD_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b03");
    private static final UUID TENANT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b04");

    private static final Instant PERIOD_START = Instant.parse("2026-03-01T00:00:00Z");
    // The window is inclusive of the whole end day, so it closes at the start of the next day.
    private static final Instant PERIOD_END = Instant.parse("2026-03-16T00:00:00Z");

    @Mock
    private TimekeepingEntryRepository timekeepingEntryRepository;

    @Mock
    private TimePeriodRepository timePeriodRepository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @InjectMocks
    private TimekeepingApprovalServiceImpl service;

    private static TimePeriod period() {
        TimePeriod period = new TimePeriod();
        period.setTimePeriodId(PERIOD_ID);
        period.setTenantId(TENANT_ID);
        period.setStartDate(LocalDate.of(2026, 3, 1));
        period.setEndDate(LocalDate.of(2026, 3, 15));
        return period;
    }

    private static ExtPersonReplica person(UUID personId, String firstName, String preferredName) {
        ExtPersonReplica replica = new ExtPersonReplica();
        replica.setPersonId(personId);
        replica.setFirstName(firstName);
        replica.setPreferredName(preferredName);
        replica.setLastName("Smith");
        return replica;
    }

    private static TimekeepingEntry entry(ApprovalStatus status) {
        TimekeepingEntry entry = new TimekeepingEntry();
        entry.setTimekeepingEntryId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4bff"));
        entry.setEmployeeId(PERSON_ID);
        entry.setSessionStartTime(Instant.parse("2026-03-02T13:00:00Z"));
        entry.setSessionEndTime(Instant.parse("2026-03-02T21:00:00Z"));
        entry.setApprovalStatus(status);
        entry.setAssociatedWorkOrderId(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4bfe"));
        return entry;
    }

    private void stubPeriodEntries(List<TimekeepingEntry> entries) {
        when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(period()));
        when(timekeepingEntryRepository
                        .findByEmployeeIdAndSessionStartTimeGreaterThanEqualAndSessionEndTimeLessThanEqual(
                                PERSON_ID, PERIOD_START, PERIOD_END))
                .thenReturn(entries);
    }

    @Nested
    @DisplayName("listApprovalPeople")
    class ListApprovalPeople {

        @Test
        void joinsReplicaIdentityToTheLocalEmployeeNumber() {
            when(timekeepingEntryRepository.findDistinctEmployeeIds()).thenReturn(List.of(PERSON_ID, OTHER_PERSON_ID));
            when(extPersonReplicaRepository.findAllById(List.of(PERSON_ID, OTHER_PERSON_ID)))
                    .thenReturn(List.of(person(PERSON_ID, "Jane", "Janie"), person(OTHER_PERSON_ID, "Robert", null)));
            when(employeeRepository.findByPersonIdIn(List.of(PERSON_ID, OTHER_PERSON_ID)))
                    .thenReturn(List.of(Employee.builder()
                            .personId(PERSON_ID)
                            .employeeNumber("EMP-0001")
                            .build()));

            List<ApprovalPersonDto> people = service.listApprovalPeople();

            assertThat(people).hasSize(2);
            // Preferred name wins over the given name when one is on file.
            assertThat(people.get(0).getDisplayName()).isEqualTo("Janie Smith");
            assertThat(people.get(0).getEmployeeNumber()).isEqualTo("EMP-0001");
            assertThat(people.get(1).getDisplayName()).isEqualTo("Robert Smith");
            // No employment row means no employee number, not a failed read.
            assertThat(people.get(1).getEmployeeNumber()).isNull();
        }

        @Test
        void fallsBackToThePersonIdWhenNoNameIsOnFile() {
            when(timekeepingEntryRepository.findDistinctEmployeeIds()).thenReturn(List.of(PERSON_ID));
            when(extPersonReplicaRepository.findAllById(List.of(PERSON_ID)))
                    .thenReturn(List.of(person(PERSON_ID, null, "  ")));
            when(employeeRepository.findByPersonIdIn(List.of(PERSON_ID))).thenReturn(List.of());

            assertThat(service.listApprovalPeople().get(0).getDisplayName()).isEqualTo(PERSON_ID.toString());
        }

        @Test
        void returnsEmptyWithoutTouchingTheReplicaWhenNoEntriesExist() {
            when(timekeepingEntryRepository.findDistinctEmployeeIds()).thenReturn(List.of());

            assertThat(service.listApprovalPeople()).isEmpty();
            verifyNoInteractions(extPersonReplicaRepository, employeeRepository);
        }
    }

    @Nested
    @DisplayName("listTimePeriods")
    class ListTimePeriods {

        @Test
        void scopesToTheTenantWhenOneIsGiven() {
            when(timePeriodRepository.findAllByTenantIdOrderByStartDateDesc(TENANT_ID))
                    .thenReturn(List.of(period()));

            List<TimePeriodDto> periods = service.listTimePeriods(TENANT_ID);

            assertThat(periods).hasSize(1);
            assertThat(periods.get(0).getTimePeriodId()).isEqualTo(PERIOD_ID);
            assertThat(periods.get(0).getStartDate()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(periods.get(0).getEndDate()).isEqualTo(LocalDate.of(2026, 3, 15));
        }

        @Test
        void readsEveryPeriodWhenNoTenantIsGiven() {
            when(timePeriodRepository.findAll()).thenReturn(List.of(period()));

            assertThat(service.listTimePeriods(null)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("listTimekeepingEntries")
    class ListTimekeepingEntries {

        @Test
        void mapsEntriesInsideTheWholePeriodIncludingItsLastDay() {
            stubPeriodEntries(List.of(entry(ApprovalStatus.PENDING_APPROVAL)));

            List<TimekeepingEntryDto> entries = service.listTimekeepingEntries(PERSON_ID, PERIOD_ID);

            assertThat(entries).hasSize(1);
            assertThat(entries.get(0).getEmployeeId()).isEqualTo(PERSON_ID);
            assertThat(entries.get(0).getApprovalStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
            assertThat(entries.get(0).getSessionEndTime()).isEqualTo(Instant.parse("2026-03-02T21:00:00Z"));
        }

        @Test
        void failsWhenThePeriodDoesNotExist() {
            when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.listTimekeepingEntries(PERSON_ID, PERIOD_ID))
                    .isInstanceOf(EntityNotFoundException.class)
                    .hasMessageContaining(PERIOD_ID.toString());
        }
    }

    @Nested
    @DisplayName("getTimePeriodApproval")
    class GetTimePeriodApproval {

        @Test
        void reportsPendingWhileAnyEntryIsStillPending() {
            stubPeriodEntries(List.of(
                    entry(ApprovalStatus.PENDING_APPROVAL),
                    entry(ApprovalStatus.APPROVED),
                    entry(ApprovalStatus.REJECTED)));

            TimePeriodApprovalDto approval = service.getTimePeriodApproval(PERSON_ID, PERIOD_ID);

            assertThat(approval.getOverallStatus()).isEqualTo(ApprovalStatus.PENDING_APPROVAL);
            assertThat(approval.getTotalCount()).isEqualTo(3);
            assertThat(approval.getPendingCount()).isEqualTo(1);
            assertThat(approval.getApprovedCount()).isEqualTo(1);
            assertThat(approval.getRejectedCount()).isEqualTo(1);
        }

        @Test
        void reportsRejectedWhenNothingIsPendingAndSomethingWasRejected() {
            stubPeriodEntries(List.of(entry(ApprovalStatus.APPROVED), entry(ApprovalStatus.REJECTED)));

            assertThat(service.getTimePeriodApproval(PERSON_ID, PERIOD_ID).getOverallStatus())
                    .isEqualTo(ApprovalStatus.REJECTED);
        }

        @Test
        void reportsApprovedWhenEveryEntryIsApproved() {
            stubPeriodEntries(List.of(entry(ApprovalStatus.APPROVED)));

            assertThat(service.getTimePeriodApproval(PERSON_ID, PERIOD_ID).getOverallStatus())
                    .isEqualTo(ApprovalStatus.APPROVED);
        }

        @Test
        void treatsAnEmptyPeriodAsApproved() {
            stubPeriodEntries(List.of());

            TimePeriodApprovalDto approval = service.getTimePeriodApproval(PERSON_ID, PERIOD_ID);

            assertThat(approval.getTotalCount()).isZero();
            assertThat(approval.getOverallStatus()).isEqualTo(ApprovalStatus.APPROVED);
        }

        @Test
        void failsWhenThePeriodDoesNotExist() {
            when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getTimePeriodApproval(PERSON_ID, PERIOD_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("approvePeriod / rejectPeriod")
    class PeriodDecisions {

        private void stubPendingEntries(List<TimekeepingEntry> pending) {
            when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.of(period()));
            when(timekeepingEntryRepository
                            .findByEmployeeIdAndApprovalStatusAndSessionStartTimeGreaterThanEqualAndSessionEndTimeLessThanEqual(
                                    eq(PERSON_ID), eq(ApprovalStatus.PENDING_APPROVAL), any(), any()))
                    .thenReturn(pending);
        }

        @Test
        void approvesEveryPendingEntryInThePeriod() {
            TimekeepingEntry pending = entry(ApprovalStatus.PENDING_APPROVAL);
            stubPendingEntries(List.of(pending));

            TimePeriodDecisionResponse response = service.approvePeriod(PERIOD_ID, PERSON_ID);

            assertThat(pending.getApprovalStatus()).isEqualTo(ApprovalStatus.APPROVED);
            assertThat(response.getStatus()).isEqualTo("APPROVED");
            assertThat(response.getProcessedCount()).isEqualTo(1);
            assertThat(response.getPersonId()).isEqualTo(PERSON_ID);
            verify(timekeepingEntryRepository).saveAll(List.of(pending));
        }

        @Test
        void reportsZeroProcessedWhenNothingIsPending() {
            stubPendingEntries(List.of());

            assertThat(service.approvePeriod(PERIOD_ID, PERSON_ID).getProcessedCount())
                    .isZero();
        }

        @Test
        void rejectsEveryPendingEntryAndStampsTheReason() {
            TimekeepingEntry pending = entry(ApprovalStatus.PENDING_APPROVAL);
            stubPendingEntries(List.of(pending));

            TimePeriodDecisionResponse response = service.rejectPeriod(PERIOD_ID, PERSON_ID, "missing break");

            assertThat(pending.getApprovalStatus()).isEqualTo(ApprovalStatus.REJECTED);
            assertThat(pending.getCorrectionReason()).isEqualTo("missing break");
            assertThat(response.getStatus()).isEqualTo("REJECTED");
            assertThat(response.getProcessedCount()).isEqualTo(1);

            ArgumentCaptor<List<TimekeepingEntry>> saved = ArgumentCaptor.captor();
            verify(timekeepingEntryRepository).saveAll(saved.capture());
            assertThat(saved.getValue()).containsExactly(pending);
        }

        @Test
        void approveFailsWhenThePeriodDoesNotExist() {
            when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.approvePeriod(PERIOD_ID, PERSON_ID))
                    .isInstanceOf(EntityNotFoundException.class);
        }

        @Test
        void rejectFailsWhenThePeriodDoesNotExist() {
            when(timePeriodRepository.findById(PERIOD_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.rejectPeriod(PERIOD_ID, PERSON_ID, "why"))
                    .isInstanceOf(EntityNotFoundException.class);
        }
    }
}
