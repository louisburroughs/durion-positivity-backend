package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link EmployeeServiceImpl} under the ADR-0044 Phase 3.2 split (#875): identity
 * reads come from the {@code ext_people_contact_person} replica, and identity writes leave the
 * module as {@code people-contact.person.upsert-requested} commands.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeProfileMappingTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000001");

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private EmployeeOffboardingRetryRepository offboardingRetryRepository;

    @Mock
    private PeopleEventPublisher peopleEventPublisher;

    private EmployeeServiceImpl service() {
        return new EmployeeServiceImpl(
                TEST_CLOCK,
                extPersonReplicaRepository,
                employeeRepository,
                offboardingRetryRepository,
                peopleEventPublisher);
    }

    private ExtPersonReplica person() {
        return ExtPersonReplica.builder()
                .personId(PERSON_ID)
                .firstName("Marcus")
                .lastName("Webb")
                .primaryEmail("marcus.webb@durion.internal")
                .primaryPhone("555-0100")
                .secondaryPhone("555-0101")
                .personCreatedAt(Instant.parse("2024-01-01T09:30:00Z"))
                .personUpdatedAt(Instant.parse("2024-02-01T14:05:00Z"))
                .aggregateVersion(0)
                .updatedAt(Instant.parse("2024-02-01T14:05:00Z"))
                .build();
    }

    /** Employment record carrying the employee-specific attributes that live on Employee. */
    private Employee employee() {
        return Employee.builder()
                .personId(PERSON_ID)
                .employeeNumber("EMP-0001")
                .status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.parse("2024-01-01"))
                .build();
    }

    @Test
    void getEmployee_mapsCreatedAndUpdatedTimestampsFromReplica() {
        when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(person()));
        when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee()));

        EmployeeProfileDto profile = service().getEmployee(PERSON_ID);

        assertThat(profile.getCreatedAt()).isEqualTo(Instant.parse("2024-01-01T09:30:00Z"));
        assertThat(profile.getUpdatedAt()).isEqualTo(Instant.parse("2024-02-01T14:05:00Z"));
    }

    @Test
    void getEmployee_mapsFirstAndLastNameFromReplica() {
        when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(person()));
        when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee()));

        EmployeeProfileDto profile = service().getEmployee(PERSON_ID);

        assertThat(profile.getFirstName()).isEqualTo("Marcus");
        assertThat(profile.getLastName()).isEqualTo("Webb");
    }

    @Test
    void getEmployee_mapsContactFromReplicaColumns() {
        when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(person()));
        when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee()));

        EmployeeProfileDto profile = service().getEmployee(PERSON_ID);

        assertThat(profile.getContactInfo()).isNotNull();
        assertThat(profile.getContactInfo().getPrimaryEmail()).isEqualTo("marcus.webb@durion.internal");
        assertThat(profile.getContactInfo().getPrimaryPhone()).isEqualTo("555-0100");
        assertThat(profile.getContactInfo().getSecondaryPhone()).isEqualTo("555-0101");
    }

    @Test
    void getEmployee_servesProfileFromEmployeeRowWhileReplicaLags() {
        when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.empty());
        when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee()));

        EmployeeProfileDto profile = service().getEmployee(PERSON_ID);

        assertThat(profile.getEmployeeNumber()).isEqualTo("EMP-0001");
        assertThat(profile.getFirstName()).isNull();
    }

    private CreateEmployeeRequest createRequest(String firstName, String lastName) {
        CreateEmployeeRequest request = new CreateEmployeeRequest();
        request.setFirstName(firstName);
        request.setLastName(lastName);
        request.setEmployeeNumber("EMP-0001");
        request.setStatus(EmployeeStatus.ACTIVE);
        request.setHireDate(LocalDate.parse("2024-01-01"));
        return request;
    }

    @Test
    void createEmployee_sendsIdentityAsUpsertCommandAndEchoesRequest() {
        when(employeeRepository.save(org.mockito.ArgumentMatchers.any(Employee.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        EmployeeProfileDto profile = service().createEmployee(createRequest("Marcus", "Webb"));

        ArgumentCaptor<PersonUpsertRequestedV1> captor = ArgumentCaptor.forClass(PersonUpsertRequestedV1.class);
        verify(peopleEventPublisher).requestPersonUpsert(captor.capture());
        assertThat(captor.getValue().firstName()).isEqualTo("Marcus");
        assertThat(captor.getValue().lastName()).isEqualTo("Webb");
        assertThat(captor.getValue().personId()).isNotNull();
        assertThat(captor.getValue().workPhones()).isEqualTo(List.of());

        // Response echoes the request identity; the employee row references the generated id.
        assertThat(profile.getId()).isEqualTo(captor.getValue().personId());
        assertThat(profile.getFirstName()).isEqualTo("Marcus");

        ArgumentCaptor<Employee> employeeCaptor = ArgumentCaptor.forClass(Employee.class);
        verify(employeeRepository).save(employeeCaptor.capture());
        assertThat(employeeCaptor.getValue().getPersonId())
                .isEqualTo(captor.getValue().personId());
        verify(peopleEventPublisher).publishEmployeeUpdated(employeeCaptor.getValue());
    }
}
