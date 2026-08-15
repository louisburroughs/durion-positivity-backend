package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.dto.UpdateStaffingAssignmentRequest;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Staffing assignment reads, updates and endings. Creation's primary-flag defaulting is covered
 * by {@code com.positivity.people.service.StaffingAssignmentServiceTest}; this class covers the
 * rest of the lifecycle — overlap conflicts, person/location validation, primary demotion on
 * update, and ending an open-ended assignment.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("StaffingAssignmentServiceImpl lifecycle")
class StaffingAssignmentLifecycleTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 1);
    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a01");
    private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a02");
    private static final UUID ASSIGNMENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a03");
    private static final UUID OTHER_ASSIGNMENT_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f6a04");
    private static final String ACTOR = "test.user";

    @Mock
    private EmployeeLocationAssignmentRepository repository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private PeopleEventPublisher peopleEventPublisher;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private LocationReferenceService locationReferenceService;

    private StaffingAssignmentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new StaffingAssignmentServiceImpl(
                repository,
                extPersonReplicaRepository,
                peopleEventPublisher,
                employeeRepository,
                locationReferenceService,
                Clock.fixed(NOW, ZoneOffset.UTC));

        when(extPersonReplicaRepository.findById(PERSON_ID))
                .thenReturn(Optional.of(ExtPersonReplica.builder()
                        .personId(PERSON_ID)
                        .aggregateVersion(0)
                        .updatedAt(NOW)
                        .build()));
        when(employeeRepository.findByPersonId(PERSON_ID))
                .thenReturn(Optional.of(Employee.builder()
                        .personId(PERSON_ID)
                        .status(EmployeeStatus.ACTIVE)
                        .build()));
        when(locationReferenceService.isLocationActive(LOCATION_ID)).thenReturn(true);
        when(repository.save(any(EmployeeLocationAssignment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static EmployeeLocationAssignment assignment(UUID id, LocalDate from, LocalDate to, boolean primary) {
        return EmployeeLocationAssignment.builder()
                .id(id)
                .employee(Employee.builder().personId(PERSON_ID).build())
                .locationId(LOCATION_ID)
                .role("TECHNICIAN")
                .isPrimary(primary)
                .effectiveFrom(from)
                .effectiveTo(to)
                .status(AssignmentStatus.ACTIVE)
                .createdBy(ACTOR)
                .build();
    }

    private static UpdateStaffingAssignmentRequest updateRequest(LocalDate from, LocalDate to, boolean primary) {
        return new UpdateStaffingAssignmentRequest(PERSON_ID, LOCATION_ID, "TECHNICIAN", primary, from, to);
    }

    @Nested
    @DisplayName("reads")
    class Reads {

        @Test
        void listsEveryAssignmentHeldByAPerson() {
            when(repository.findByEmployee_PersonId(PERSON_ID))
                    .thenReturn(List.of(assignment(ASSIGNMENT_ID, TODAY, null, true)));

            List<StaffingAssignmentResponse> assignments = service.findByPersonId(PERSON_ID);

            assertThat(assignments).hasSize(1);
            assertThat(assignments.get(0).getAssignmentId()).isEqualTo(ASSIGNMENT_ID);
            assertThat(assignments.get(0).getPersonId()).isEqualTo(PERSON_ID);
            assertThat(assignments.get(0).getLocationId()).isEqualTo(LOCATION_ID);
            assertThat(assignments.get(0).isPrimary()).isTrue();
            assertThat(assignments.get(0).getRole()).isEqualTo("TECHNICIAN");
            assertThat(assignments.get(0).getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            assertThat(assignments.get(0).getCreatedBy()).isEqualTo(ACTOR);
        }

        @Test
        void asksForTheAssignmentsActiveOnTodaysDate() {
            when(repository.findActiveByPersonIdAndDate(PERSON_ID, TODAY))
                    .thenReturn(List.of(assignment(ASSIGNMENT_ID, TODAY, null, true)));

            assertThat(service.findActiveByPersonId(PERSON_ID)).hasSize(1);
            verify(repository).findActiveByPersonIdAndDate(PERSON_ID, TODAY);
        }

        @Test
        void findsASingleAssignmentById() {
            when(repository.findById(ASSIGNMENT_ID))
                    .thenReturn(Optional.of(assignment(ASSIGNMENT_ID, TODAY, null, false)));

            assertThat(service.findById(ASSIGNMENT_ID)).isPresent();
        }

        @Test
        void returnsEmptyForAnUnknownAssignmentId() {
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.empty());

            assertThat(service.findById(ASSIGNMENT_ID)).isEmpty();
        }
    }

    @Nested
    @DisplayName("create validation")
    class CreateValidation {

        private CreateStaffingAssignmentRequest createRequest() {
            return new CreateStaffingAssignmentRequest(
                    PERSON_ID, LOCATION_ID, "TECHNICIAN", false, LocalDate.of(2026, 3, 1), null);
        }

        @Test
        void refusesAnOverlappingAssignmentForTheSamePersonLocationAndRole() {
            when(repository.existsOverlapping(any(), any(), any(), any(), any()))
                    .thenReturn(true);
            CreateStaffingAssignmentRequest request = createRequest();

            assertThatThrownBy(() -> service.create(request, ACTOR))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("overlapping assignment");
            verify(repository, never()).save(any());
        }

        @Test
        void refusesAnAssignmentForAPersonTheReplicaDoesNotKnow() {
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.empty());
            CreateStaffingAssignmentRequest request = createRequest();

            assertThatThrownBy(() -> service.create(request, ACTOR))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Person not found");
        }

        @Test
        void refusesAnAssignmentForANonActiveEmployee() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(Employee.builder()
                            .personId(PERSON_ID)
                            .status(EmployeeStatus.DISABLED)
                            .build()));
            CreateStaffingAssignmentRequest request = createRequest();

            assertThatThrownBy(() -> service.create(request, ACTOR))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("not active");
        }

        @Test
        void refusesAnAssignmentForAPersonWithNoEmploymentRow() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());
            CreateStaffingAssignmentRequest request = createRequest();

            assertThatThrownBy(() -> service.create(request, ACTOR))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("not active");
        }

        @Test
        void refusesAnAssignmentToAnInactiveLocation() {
            when(locationReferenceService.isLocationActive(LOCATION_ID)).thenReturn(false);
            CreateStaffingAssignmentRequest request = createRequest();

            assertThatThrownBy(() -> service.create(request, ACTOR))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Location not found or inactive");
        }

        @Test
        void demotesTheOverlappingPrimaryToTheDayBeforeTheNewOne() {
            EmployeeLocationAssignment existingPrimary =
                    assignment(OTHER_ASSIGNMENT_ID, LocalDate.of(2026, 1, 1), null, true);
            when(repository.findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(PERSON_ID, AssignmentStatus.ACTIVE))
                    .thenReturn(Optional.of(existingPrimary));

            CreateStaffingAssignmentRequest request = new CreateStaffingAssignmentRequest(
                    PERSON_ID, LOCATION_ID, "SERVICE_ADVISOR", true, LocalDate.of(2026, 3, 1), null);
            service.create(request, ACTOR);

            assertThat(existingPrimary.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 2, 28));
            assertThat(existingPrimary.getStatus()).isEqualTo(AssignmentStatus.ENDED);
        }

        @Test
        void clampsTheDemotionDateToTheExistingPrimarysOwnStart() {
            // The new primary starts on the same day the old one did, so it cannot be demoted
            // to the day before — that would put its end before its start.
            EmployeeLocationAssignment existingPrimary =
                    assignment(OTHER_ASSIGNMENT_ID, LocalDate.of(2026, 3, 1), null, true);
            when(repository.findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(PERSON_ID, AssignmentStatus.ACTIVE))
                    .thenReturn(Optional.of(existingPrimary));

            CreateStaffingAssignmentRequest request = new CreateStaffingAssignmentRequest(
                    PERSON_ID, LOCATION_ID, "SERVICE_ADVISOR", true, LocalDate.of(2026, 3, 1), null);
            service.create(request, ACTOR);

            assertThat(existingPrimary.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 3, 1));
        }

        @Test
        void leavesANonOverlappingPrimaryAlone() {
            EmployeeLocationAssignment pastPrimary =
                    assignment(OTHER_ASSIGNMENT_ID, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), true);
            when(repository.findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(PERSON_ID, AssignmentStatus.ACTIVE))
                    .thenReturn(Optional.of(pastPrimary));

            CreateStaffingAssignmentRequest request = new CreateStaffingAssignmentRequest(
                    PERSON_ID, LOCATION_ID, "SERVICE_ADVISOR", true, LocalDate.of(2026, 3, 1), null);
            service.create(request, ACTOR);

            assertThat(pastPrimary.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
            assertThat(pastPrimary.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 1, 31));
        }
    }

    @Nested
    @DisplayName("update")
    class Update {

        @Test
        void rewritesTheAssignmentAndPublishesTheFact() {
            EmployeeLocationAssignment existing = assignment(ASSIGNMENT_ID, LocalDate.of(2026, 1, 1), null, false);
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(existing));

            Optional<StaffingAssignmentResponse> response = service.update(
                    ASSIGNMENT_ID, updateRequest(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 12, 31), false), ACTOR);

            assertThat(response).isPresent();
            assertThat(existing.getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
            assertThat(existing.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
            verify(peopleEventPublisher).publishStaffingAssignmentUpdated(existing);
        }

        @Test
        void returnsEmptyForAnUnknownAssignment() {
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.empty());

            assertThat(service.update(ASSIGNMENT_ID, updateRequest(TODAY, null, false), ACTOR))
                    .isEmpty();
            // The person/location checks are skipped entirely for a missing assignment.
            verify(extPersonReplicaRepository, never()).findById(any());
        }

        @Test
        void refusesAnUpdateThatWouldOverlapAnotherAssignment() {
            when(repository.findById(ASSIGNMENT_ID))
                    .thenReturn(Optional.of(assignment(ASSIGNMENT_ID, TODAY, null, false)));
            when(repository.existsOverlappingExcludingId(any(), any(), any(), any(), any(), any()))
                    .thenReturn(true);
            UpdateStaffingAssignmentRequest request = updateRequest(TODAY, null, false);

            assertThatThrownBy(() -> service.update(ASSIGNMENT_ID, request, ACTOR))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("overlapping assignment");
        }

        @Test
        void demotesADifferentOverlappingPrimaryWhenPromotingThisOne() {
            when(repository.findById(ASSIGNMENT_ID))
                    .thenReturn(Optional.of(assignment(ASSIGNMENT_ID, TODAY, null, false)));
            EmployeeLocationAssignment otherPrimary =
                    assignment(OTHER_ASSIGNMENT_ID, LocalDate.of(2026, 1, 1), null, true);
            when(repository.findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(PERSON_ID, AssignmentStatus.ACTIVE))
                    .thenReturn(Optional.of(otherPrimary));

            service.update(ASSIGNMENT_ID, updateRequest(LocalDate.of(2026, 3, 1), null, true), ACTOR);

            assertThat(otherPrimary.getStatus()).isEqualTo(AssignmentStatus.ENDED);
            assertThat(otherPrimary.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 2, 28));
        }

        @Test
        void doesNotDemoteTheAssignmentBeingUpdated() {
            EmployeeLocationAssignment self = assignment(ASSIGNMENT_ID, LocalDate.of(2026, 1, 1), null, true);
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(self));
            when(repository.findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(PERSON_ID, AssignmentStatus.ACTIVE))
                    .thenReturn(Optional.of(self));

            service.update(ASSIGNMENT_ID, updateRequest(LocalDate.of(2026, 3, 1), null, true), ACTOR);

            assertThat(self.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        }

        @Test
        void leavesANonOverlappingPrimaryAloneOnUpdate() {
            when(repository.findById(ASSIGNMENT_ID))
                    .thenReturn(Optional.of(assignment(ASSIGNMENT_ID, TODAY, null, false)));
            EmployeeLocationAssignment pastPrimary =
                    assignment(OTHER_ASSIGNMENT_ID, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), true);
            when(repository.findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(PERSON_ID, AssignmentStatus.ACTIVE))
                    .thenReturn(Optional.of(pastPrimary));

            service.update(ASSIGNMENT_ID, updateRequest(LocalDate.of(2026, 3, 1), null, true), ACTOR);

            assertThat(pastPrimary.getStatus()).isEqualTo(AssignmentStatus.ACTIVE);
        }
    }

    @Nested
    @DisplayName("end")
    class End {

        @Test
        void closesAnOpenEndedAssignmentAtToday() {
            EmployeeLocationAssignment open = assignment(ASSIGNMENT_ID, LocalDate.of(2026, 1, 1), null, true);
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(open));

            service.end(ASSIGNMENT_ID);

            assertThat(open.getStatus()).isEqualTo(AssignmentStatus.ENDED);
            assertThat(open.getEffectiveTo()).isEqualTo(TODAY);
            verify(peopleEventPublisher).publishStaffingAssignmentUpdated(open);
        }

        @Test
        void keepsAnAlreadyScheduledEndDate() {
            EmployeeLocationAssignment scheduled =
                    assignment(ASSIGNMENT_ID, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30), true);
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.of(scheduled));

            service.end(ASSIGNMENT_ID);

            assertThat(scheduled.getEffectiveTo()).isEqualTo(LocalDate.of(2026, 6, 30));
        }

        @Test
        void failsWhenTheAssignmentDoesNotExist() {
            when(repository.findById(ASSIGNMENT_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.end(ASSIGNMENT_ID))
                    .isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Assignment not found")
                    .extracting(exception -> ((ResponseStatusException) exception).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }
}
