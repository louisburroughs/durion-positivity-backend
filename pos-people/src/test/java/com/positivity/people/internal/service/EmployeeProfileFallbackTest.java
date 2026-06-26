package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.PersonRepository;
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
 * Unit tests for {@link EmployeeServiceImpl#getEmployee} name/contact fallback.
 *
 * Persons created via the People path (and seed data) populate the typed
 * {@code firstName}/{@code lastName} and contact columns, but not the
 * employee-specific {@code legalName}/{@code contactInfoJson}. The profile must
 * still surface a name and contact for those records.
 */
@ExtendWith(MockitoExtension.class)
class EmployeeProfileFallbackTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final UUID PERSON_ID = UUID.fromString("01960011-0000-7000-8000-000000000001");

    @Mock
    private PersonRepository personRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private EmployeeOffboardingRetryRepository offboardingRetryRepository;

    @Mock
    private PersonWorkPhoneService workPhoneService;

    @Mock
    private PersonEmailService emailService;

    @org.junit.jupiter.api.BeforeEach
    void defaultEmailStub() {
        org.mockito.Mockito.lenient()
                .when(emailService.getEmails(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new PersonEmailService.EmailPair(null, null));
    }

    private EmployeeServiceImpl service() {
        return new EmployeeServiceImpl(
                TEST_CLOCK,
                personRepository,
                employeeRepository,
                workPhoneService,
                emailService,
                offboardingRetryRepository,
                new ObjectMapper());
    }

    private Person columnSourcedPerson() {
        Person person = new Person();
        person.setId(PERSON_ID);
        person.setFirstName("Marcus");
        person.setLastName("Webb");
        person.setCreatedAt(Instant.parse("2024-01-01T09:30:00Z"));
        person.setUpdatedAt(Instant.parse("2024-02-01T14:05:00Z"));
        // legalName and contactInfoJson intentionally left null (People-path / seed record).
        return person;
    }

    /** Employment record carrying the employee-specific attributes that moved off Person. */
    private Employee columnSourcedEmployee() {
        return Employee.builder()
                .personId(PERSON_ID)
                .employeeNumber("EMP-0001")
                .status(EmployeeStatus.ACTIVE)
                .hireDate(LocalDate.parse("2024-01-01"))
                .build();
    }

    @Test
    void getEmployee_mapsCreatedAndUpdatedTimestamps() {
        when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(columnSourcedPerson()));

        EmployeeProfileDto profile = service().getEmployee(PERSON_ID);

        assertThat(profile.getCreatedAt()).isEqualTo(Instant.parse("2024-01-01T09:30:00Z"));
        assertThat(profile.getUpdatedAt()).isEqualTo(Instant.parse("2024-02-01T14:05:00Z"));
    }

    @Test
    void getEmployee_fallsBackToFirstLastNameWhenLegalNameMissing() {
        when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(columnSourcedPerson()));

        EmployeeProfileDto profile = service().getEmployee(PERSON_ID);

        assertThat(profile.getLegalName()).isEqualTo("Marcus Webb");
    }

    @Test
    void getEmployee_fallsBackToContactColumnsWhenJsonMissing() {
        when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(columnSourcedPerson()));
        when(workPhoneService.getWorkPhones(PERSON_ID)).thenReturn(List.of("555-0100", "555-0101"));
        when(emailService.getEmails(PERSON_ID))
                .thenReturn(new PersonEmailService.EmailPair("marcus.webb@durion.internal", null));

        EmployeeProfileDto profile = service().getEmployee(PERSON_ID);

        assertThat(profile.getContactInfo()).isNotNull();
        assertThat(profile.getContactInfo().getPrimaryEmail()).isEqualTo("marcus.webb@durion.internal");
        assertThat(profile.getContactInfo().getPrimaryPhone()).isEqualTo("555-0100");
        assertThat(profile.getContactInfo().getSecondaryPhone()).isEqualTo("555-0101");
    }

    @Test
    void getEmployee_prefersExplicitLegalNameOverFirstLast() {
        Person person = columnSourcedPerson();
        person.setLegalName("Marcus Aurelius Webb");
        when(personRepository.findById(PERSON_ID)).thenReturn(Optional.of(person));

        EmployeeProfileDto profile = service().getEmployee(PERSON_ID);

        assertThat(profile.getLegalName()).isEqualTo("Marcus Aurelius Webb");
    }

    private CreateEmployeeRequest createRequest(String legalName) {
        CreateEmployeeRequest request = new CreateEmployeeRequest();
        request.setLegalName(legalName);
        request.setEmployeeNumber("EMP-0001");
        request.setStatus(EmployeeStatus.ACTIVE);
        request.setHireDate(LocalDate.parse("2024-01-01"));
        return request;
    }

    @Test
    void createEmployee_syncsFirstAndLastNameFromLegalName() {
        when(personRepository.save(any(Person.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().createEmployee(createRequest("Marcus Webb"));

        ArgumentCaptor<Person> captor = ArgumentCaptor.forClass(Person.class);
        verify(personRepository).save(captor.capture());
        assertThat(captor.getValue().getFirstName()).isEqualTo("Marcus");
        assertThat(captor.getValue().getLastName()).isEqualTo("Webb");
    }

    @Test
    void createEmployee_putsTrailingTokensInLastName() {
        when(personRepository.save(any(Person.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().createEmployee(createRequest("Marcus Aurelius Webb"));

        ArgumentCaptor<Person> captor = ArgumentCaptor.forClass(Person.class);
        verify(personRepository).save(captor.capture());
        assertThat(captor.getValue().getFirstName()).isEqualTo("Marcus");
        assertThat(captor.getValue().getLastName()).isEqualTo("Aurelius Webb");
    }
}
