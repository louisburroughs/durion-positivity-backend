package com.positivity.people.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.positivity.people.internal.client.LocationReferenceClient;
import com.positivity.people.internal.dto.CreateStaffingAssignmentRequest;
import com.positivity.people.internal.dto.StaffingAssignmentResponse;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.service.StaffingAssignmentServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for StaffingAssignmentServiceImpl creation, focused on the
 * primary-flag defaulting: a person's first active assignment becomes primary
 * automatically so GET /v1/people/me/primary-location (and its
 * service-to-service consumers) keeps resolving a location.
 */
@ExtendWith(MockitoExtension.class)
class StaffingAssignmentServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-01-15T10:00:00Z"), ZoneOffset.UTC);
    private static final UUID PERSON_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final String ACTOR = "test.user";

    @Mock
    private EmployeeLocationAssignmentRepository repository;

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private com.positivity.people.internal.config.PeopleEventPublisher peopleEventPublisher;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private LocationReferenceClient locationReferenceClient;

    private StaffingAssignmentServiceImpl service;

    private Employee employee;

    @BeforeEach
    void setUp() {
        service = new StaffingAssignmentServiceImpl(
                repository,
                extPersonReplicaRepository,
                peopleEventPublisher,
                employeeRepository,
                locationReferenceClient,
                FIXED_CLOCK);

        employee = Employee.builder()
                .personId(PERSON_ID)
                .status(EmployeeStatus.ACTIVE)
                .build();
        when(extPersonReplicaRepository.findById(PERSON_ID))
                .thenReturn(Optional.of(ExtPersonReplica.builder()
                        .personId(PERSON_ID)
                        .aggregateVersion(0)
                        .updatedAt(java.time.Instant.now())
                        .build()));
        when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee));
        when(locationReferenceClient.isLocationActive(LOCATION_ID)).thenReturn(true);
        when(repository.existsOverlapping(any(), any(), any(), any(), any())).thenReturn(false);
        when(repository.save(any(EmployeeLocationAssignment.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private CreateStaffingAssignmentRequest request(boolean primary) {
        return new CreateStaffingAssignmentRequest(
                PERSON_ID, LOCATION_ID, "TECHNICIAN", primary, LocalDate.of(2026, 2, 1), null);
    }

    @Test
    void create_nonPrimaryRequest_defaultsToPrimaryWhenPersonHasNoActivePrimary() {
        when(repository.findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(PERSON_ID, AssignmentStatus.ACTIVE))
                .thenReturn(Optional.empty());

        StaffingAssignmentResponse response = service.create(request(false), ACTOR);

        assertThat(response.isPrimary()).isTrue();
    }

    @Test
    void create_nonPrimaryRequest_staysNonPrimaryWhenActivePrimaryExists() {
        EmployeeLocationAssignment existingPrimary = EmployeeLocationAssignment.builder()
                .employee(employee)
                .locationId(LOCATION_ID)
                .role("SERVICE_ADVISOR")
                .isPrimary(true)
                .effectiveFrom(LocalDate.of(2026, 1, 1))
                .build();
        when(repository.findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(PERSON_ID, AssignmentStatus.ACTIVE))
                .thenReturn(Optional.of(existingPrimary));

        StaffingAssignmentResponse response = service.create(request(false), ACTOR);

        assertThat(response.isPrimary()).isFalse();
    }

    @Test
    void create_primaryRequest_remainsPrimary() {
        when(repository.findFirstByEmployee_PersonIdAndIsPrimaryTrueAndStatus(PERSON_ID, AssignmentStatus.ACTIVE))
                .thenReturn(Optional.empty());

        StaffingAssignmentResponse response = service.create(request(true), ACTOR);

        assertThat(response.isPrimary()).isTrue();
    }
}
