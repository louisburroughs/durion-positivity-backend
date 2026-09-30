package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Offboarding policy application and its idempotence (#2121). */
@ExtendWith(MockitoExtension.class)
@DisplayName("OffboardingAssignmentEnder")
class OffboardingAssignmentEnderTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-01T12:00:00Z"), ZoneOffset.UTC);

    @Mock
    private EmployeeLocationAssignmentRepository repository;

    @Mock
    private PeopleEventPublisher publisher;

    private OffboardingAssignmentEnder ender;

    @BeforeEach
    void setUp() {
        ender = new OffboardingAssignmentEnder(CLOCK, repository, publisher);
    }

    private EmployeeLocationAssignment assignment(LocalDate to, AssignmentStatus status) {
        return EmployeeLocationAssignment.builder()
                .id(UUID.randomUUID())
                .employee(Employee.builder().personId(PERSON_ID).build())
                .locationId(UUID.randomUUID())
                .role("TECHNICIAN")
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .effectiveTo(to)
                .status(status)
                .build();
    }

    @Test
    @DisplayName("IMMEDIATE is idempotent: a second run over the ended set changes and publishes nothing")
    void immediateIsIdempotent() {
        EmployeeLocationAssignment active = assignment(null, AssignmentStatus.ACTIVE);
        when(repository.findByEmployee_PersonId(PERSON_ID)).thenReturn(List.of(active));
        when(repository.save(any(EmployeeLocationAssignment.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(ender.apply(PERSON_ID, AssignmentTerminationPolicy.IMMEDIATE, null, "a"))
                .isEqualTo(1);
        assertThat(ender.apply(PERSON_ID, AssignmentTerminationPolicy.IMMEDIATE, null, "a"))
                .isZero();

        verify(publisher).publishStaffingAssignmentUpdated(active);
    }

    @Test
    @DisplayName("GRACE_PERIOD is idempotent: a second run leaves an already-dated assignment alone")
    void gracePeriodIsIdempotent() {
        EmployeeLocationAssignment active = assignment(null, AssignmentStatus.ACTIVE);
        when(repository.findByEmployee_PersonId(PERSON_ID)).thenReturn(List.of(active));
        when(repository.save(any(EmployeeLocationAssignment.class))).thenAnswer(i -> i.getArgument(0));
        LocalDate graceEnd = TODAY.plusDays(30);

        assertThat(ender.apply(PERSON_ID, AssignmentTerminationPolicy.GRACE_PERIOD, graceEnd, "a"))
                .isEqualTo(1);
        assertThat(ender.apply(PERSON_ID, AssignmentTerminationPolicy.GRACE_PERIOD, graceEnd, "a"))
                .isZero();

        assertThat(active.getEffectiveTo()).isEqualTo(graceEnd);
        assertThat(active.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        verify(publisher).publishStaffingAssignmentUpdated(active);
    }

    @Test
    @DisplayName("GRACE_PERIOD without an end date cannot be applied")
    void gracePeriodNeedsAnEndDate() {
        assertThatThrownBy(() -> ender.apply(PERSON_ID, AssignmentTerminationPolicy.GRACE_PERIOD, null, "a"))
                .isInstanceOf(IllegalStateException.class);
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("IMMEDIATE ends every ACTIVE assignment today, publishes each, and leaves ended ones alone")
    void immediateEndsEveryActiveAssignment() {
        EmployeeLocationAssignment openEnded = assignment(null, AssignmentStatus.ACTIVE);
        EmployeeLocationAssignment endsLater = assignment(TODAY.plusMonths(6), AssignmentStatus.ACTIVE);
        EmployeeLocationAssignment endsSooner = assignment(TODAY.minusDays(14), AssignmentStatus.ACTIVE);
        EmployeeLocationAssignment alreadyEnded = assignment(TODAY.minusDays(30), AssignmentStatus.ENDED);
        when(repository.findByEmployee_PersonId(PERSON_ID))
                .thenReturn(List.of(openEnded, endsLater, endsSooner, alreadyEnded));
        when(repository.save(any(EmployeeLocationAssignment.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(ender.apply(PERSON_ID, AssignmentTerminationPolicy.IMMEDIATE, null, "a"))
                .isEqualTo(3);

        assertThat(openEnded.getEffectiveTo()).isEqualTo(TODAY);
        assertThat(endsLater.getEffectiveTo()).isEqualTo(TODAY);
        assertThat(endsSooner.getEffectiveTo()).isEqualTo(TODAY.minusDays(14));
        assertThat(List.of(openEnded, endsLater, endsSooner)).allMatch(a -> a.getStatus() == AssignmentStatus.ENDED);
        assertThat(alreadyEnded.getEffectiveTo()).isEqualTo(TODAY.minusDays(30));
        verify(publisher, never()).publishStaffingAssignmentUpdated(alreadyEnded);
        verify(repository, never()).save(alreadyEnded);
        verify(publisher, times(3)).publishStaffingAssignmentUpdated(any());
    }

    @Test
    @DisplayName("an assignment that has not started yet is ended without inverting its date range")
    void notYetStartedAssignmentKeepsAWellFormedRange() {
        EmployeeLocationAssignment future = assignment(null, AssignmentStatus.ACTIVE);
        future.setEffectiveFrom(TODAY.plusMonths(1));
        when(repository.findByEmployee_PersonId(PERSON_ID)).thenReturn(List.of(future));
        when(repository.save(any(EmployeeLocationAssignment.class))).thenAnswer(i -> i.getArgument(0));

        ender.apply(PERSON_ID, AssignmentTerminationPolicy.IMMEDIATE, null, "a");

        assertThat(future.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(future.getEffectiveTo()).isEqualTo(TODAY.plusMonths(1));
    }

    @Test
    @DisplayName("GRACE_PERIOD dates open-ended and later-ending assignments, keeps them ACTIVE, skips earlier ones")
    void gracePeriodDatesAssignments() {
        LocalDate graceEnd = TODAY.plusDays(30);
        EmployeeLocationAssignment openEnded = assignment(null, AssignmentStatus.ACTIVE);
        EmployeeLocationAssignment endsLater = assignment(TODAY.plusYears(1), AssignmentStatus.ACTIVE);
        EmployeeLocationAssignment endsSooner = assignment(TODAY.plusDays(10), AssignmentStatus.ACTIVE);
        when(repository.findByEmployee_PersonId(PERSON_ID)).thenReturn(List.of(openEnded, endsLater, endsSooner));
        when(repository.save(any(EmployeeLocationAssignment.class))).thenAnswer(i -> i.getArgument(0));

        assertThat(ender.apply(PERSON_ID, AssignmentTerminationPolicy.GRACE_PERIOD, graceEnd, "a"))
                .isEqualTo(2);

        assertThat(openEnded.getEffectiveTo()).isEqualTo(graceEnd);
        assertThat(endsLater.getEffectiveTo()).isEqualTo(graceEnd);
        assertThat(endsSooner.getEffectiveTo()).isEqualTo(TODAY.plusDays(10));
        assertThat(List.of(openEnded, endsLater, endsSooner)).allMatch(a -> a.getStatus() == AssignmentStatus.ACTIVE);
        verify(publisher, never()).publishStaffingAssignmentUpdated(endsSooner);
    }

    @Test
    @DisplayName("the sweep ends an expired grace-period assignment, keeping its end date")
    void sweepEndsExpiredAssignments() {
        EmployeeLocationAssignment expired = assignment(TODAY.minusDays(1), AssignmentStatus.ACTIVE);
        givenSweepFinds(expired);

        assertThat(ender.endLingeringAssignments()).isEqualTo(1);

        assertThat(expired.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(expired.getEffectiveTo()).isEqualTo(TODAY.minusDays(1));
        verify(publisher).publishStaffingAssignmentUpdated(expired);
    }

    @Test
    @DisplayName("the sweep also ends an open-ended assignment of an offboarded employee, dating it today")
    void sweepEndsOpenEndedAssignments() {
        EmployeeLocationAssignment open = assignment(null, AssignmentStatus.ACTIVE);
        givenSweepFinds(open);

        assertThat(ender.endLingeringAssignments()).isEqualTo(1);

        assertThat(open.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        assertThat(open.getEffectiveTo()).isEqualTo(TODAY);
        verify(publisher).publishStaffingAssignmentUpdated(open);
        assertThat(OffboardingAssignmentEnder.OFFBOARDED_STATUSES)
                .containsExactlyInAnyOrder(EmployeeStatus.DISABLED, EmployeeStatus.TERMINATED);
    }

    @Test
    @DisplayName("the sweep leaves a just-changed status to the after-commit handler: cutoff is five minutes back")
    void sweepUsesTheSettleCutoff() {
        givenSweepFinds();

        assertThat(ender.endLingeringAssignments()).isZero();

        verify(repository)
                .findOpenForOffboardedEmployees(
                        TODAY,
                        Instant.parse("2026-03-01T12:00:00Z").minusSeconds(300),
                        OffboardingAssignmentEnder.OFFBOARDED_STATUSES);
        verify(publisher, never()).publishStaffingAssignmentUpdated(any());
    }

    private void givenSweepFinds(EmployeeLocationAssignment... found) {
        when(repository.findOpenForOffboardedEmployees(any(), any(), any())).thenReturn(List.of(found));
        if (found.length > 0) {
            when(repository.save(any(EmployeeLocationAssignment.class))).thenAnswer(i -> i.getArgument(0));
        }
    }
}
