package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.DisableEmployeeRequestDto;
import com.positivity.people.internal.dto.EmployeeContactInfoDto;
import com.positivity.people.internal.dto.EmployeeIdentityDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.EmployeeRoleAssignmentDto;
import com.positivity.people.internal.dto.EmployeeStatusCountsResponse;
import com.positivity.people.internal.dto.EmployeeSummaryDto;
import com.positivity.people.internal.dto.EnableEmployeeRequestDto;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.dto.UpdateEmployeeRequest;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeLocationAssignment;
import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.entity.JobRole;
import com.positivity.people.internal.enums.AssignmentStatus;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.DuplicatePolicy;
import com.positivity.people.internal.enums.EmployeeSearchInclude;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.exception.ResourceStateConflictException;
import com.positivity.people.internal.exception.SemanticValidationException;
import com.positivity.people.internal.repository.EmployeeLocationAssignmentRepository;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.JobRoleRepository;
import com.positivity.people.internal.security.PeoplePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Employment lifecycle unit coverage (ADR-0044 §6 Phase 3.2, #875): identity writes leave as
 * upsert commands, employment attributes stay local, and every mutation publishes a fact.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EmployeeServiceImpl")
class EmployeeServiceImplTest {

    private static final UUID PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a01");
    private static final UUID OTHER_PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a02");
    private static final Instant NOW = Instant.parse("2026-03-01T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Mock
    private ExtPersonReplicaRepository extPersonReplicaRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private EmployeeOffboardingRetryRepository offboardingRetryRepository;

    @Mock
    private PeopleEventPublisher peopleEventPublisher;

    @Mock
    private JobRoleRepository jobRoleRepository;

    @Mock
    private PersonUsernameService personUsernameService;

    @Mock
    private RoleAssignmentReplicaService roleAssignmentReplicaService;

    @Mock
    private EmployeeLocationAssignmentRepository employeeLocationAssignmentRepository;

    @Mock
    private LocationReferenceService locationReferenceService;

    private EmployeeServiceImpl service;

    private static final UUID JOB_ROLE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01");

    @BeforeEach
    void setUp() {
        service = new EmployeeServiceImpl(
                CLOCK,
                extPersonReplicaRepository,
                employeeRepository,
                offboardingRetryRepository,
                peopleEventPublisher,
                jobRoleRepository,
                personUsernameService,
                roleAssignmentReplicaService,
                employeeLocationAssignmentRepository,
                locationReferenceService);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static JobRole jobRole() {
        return JobRole.builder()
                .id(JOB_ROLE_ID)
                .code("LEAD_TECH")
                .name("Lead Technician")
                .active(true)
                .build();
    }

    private static CreateEmployeeRequest createRequest() {
        CreateEmployeeRequest request = new CreateEmployeeRequest();
        request.setFirstName("Jane");
        request.setLastName("Smith");
        request.setPreferredName("Janie");
        request.setEmployeeNumber("EMP-0001");
        request.setStatus(EmployeeStatus.ACTIVE);
        request.setHireDate(LocalDate.of(2026, 1, 15));
        return request;
    }

    private static UpdateEmployeeRequest updateRequest() {
        UpdateEmployeeRequest request = new UpdateEmployeeRequest();
        request.setFirstName("Jane");
        request.setLastName("Smith");
        request.setEmployeeNumber("EMP-0001");
        request.setStatus(EmployeeStatus.ACTIVE);
        request.setHireDate(LocalDate.of(2026, 1, 15));
        return request;
    }

    private static EmployeeContactInfoDto contact(String email, String primaryPhone, String secondaryPhone) {
        EmployeeContactInfoDto contactInfo = new EmployeeContactInfoDto();
        contactInfo.setPrimaryEmail(email);
        contactInfo.setPrimaryPhone(primaryPhone);
        contactInfo.setSecondaryPhone(secondaryPhone);
        return contactInfo;
    }

    private static Employee employee(EmployeeStatus status) {
        return Employee.builder()
                .id(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aaa"))
                .personId(PERSON_ID)
                .employeeNumber("EMP-0001")
                .status(status)
                .hireDate(LocalDate.of(2026, 1, 15))
                .statusEffectiveAt(Instant.parse("2026-01-15T00:00:00Z"))
                .build();
    }

    private static ExtPersonReplica replica() {
        ExtPersonReplica person = new ExtPersonReplica();
        person.setPersonId(PERSON_ID);
        person.setFirstName("Jane");
        person.setLastName("Smith");
        person.setPreferredName("Janie");
        person.setPrimaryEmail("jane@example.com");
        person.setPersonCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        person.setPersonUpdatedAt(Instant.parse("2026-02-01T00:00:00Z"));
        return person;
    }

    @Nested
    @DisplayName("resolveByEmployeeNumber")
    class ResolveByEmployeeNumber {

        @Test
        void mapsAnActiveEmployeeToItsIdentity() {
            when(employeeRepository.findByEmployeeNumberIgnoreCase("EMP-0001"))
                    .thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));

            Optional<EmployeeIdentityDto> identity = service.resolveByEmployeeNumber("EMP-0001");

            assertThat(identity).isPresent();
            assertThat(identity.get().getPersonId()).isEqualTo(PERSON_ID);
            assertThat(identity.get().getEmployeeNumber()).isEqualTo("EMP-0001");
            assertThat(identity.get().getStatus()).isEqualTo("ACTIVE");
            assertThat(identity.get().isActive()).isTrue();
        }

        @Test
        void reportsANonActiveEmployeeAsInactive() {
            when(employeeRepository.findByEmployeeNumberIgnoreCase("EMP-0001"))
                    .thenReturn(Optional.of(employee(EmployeeStatus.ON_LEAVE)));

            assertThat(service.resolveByEmployeeNumber("EMP-0001").orElseThrow().isActive())
                    .isFalse();
        }

        @Test
        void tolueratesAnEmployeeRowWithNoStatus() {
            Employee employee = employee(EmployeeStatus.ACTIVE);
            employee.setStatus(null);
            when(employeeRepository.findByEmployeeNumberIgnoreCase("EMP-0001")).thenReturn(Optional.of(employee));

            EmployeeIdentityDto identity =
                    service.resolveByEmployeeNumber("EMP-0001").orElseThrow();

            assertThat(identity.getStatus()).isNull();
            assertThat(identity.isActive()).isFalse();
        }

        @Test
        void returnsEmptyWhenNoEmployeeCarriesTheNumber() {
            when(employeeRepository.findByEmployeeNumberIgnoreCase("EMP-9999")).thenReturn(Optional.empty());

            assertThat(service.resolveByEmployeeNumber("EMP-9999")).isEmpty();
        }
    }

    @Nested
    @DisplayName("createEmployee")
    class CreateEmployee {

        @Test
        void sendsTheIdentityUpsertAndSavesTheEmploymentRow() {
            CreateEmployeeRequest request = createRequest();
            request.setContactInfo(contact("  jane@example.com ", "555-0100", "  "));

            EmployeeProfileDto profile = service.createEmployee(request);

            ArgumentCaptor<PersonUpsertRequestedV1> command = ArgumentCaptor.forClass(PersonUpsertRequestedV1.class);
            verify(peopleEventPublisher).requestPersonUpsert(command.capture());
            assertThat(command.getValue().firstName()).isEqualTo("Jane");
            assertThat(command.getValue().primaryEmail()).isEqualTo("jane@example.com");
            // Blank secondary phone is dropped rather than travelling as an empty string.
            assertThat(command.getValue().workPhones()).containsExactly("555-0100");

            ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
            verify(employeeRepository).save(saved.capture());
            assertThat(saved.getValue().getPersonId())
                    .isEqualTo(command.getValue().personId());
            assertThat(saved.getValue().getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
            assertThat(saved.getValue().getStatusEffectiveAt()).isEqualTo(NOW);
            verify(peopleEventPublisher).publishEmployeeUpdated(saved.getValue());

            // The response echoes the request identity: the replica has not caught up yet.
            assertThat(profile.getId()).isEqualTo(command.getValue().personId());
            assertThat(profile.getPreferredName()).isEqualTo("Janie");
            assertThat(profile.getEmployeeNumber()).isEqualTo("EMP-0001");
            assertThat(profile.getWarnings()).isEmpty();
        }

        @Test
        void sendsNoPhonesWhenTheRequestCarriesNoContactInfo() {
            service.createEmployee(createRequest());

            ArgumentCaptor<PersonUpsertRequestedV1> command = ArgumentCaptor.forClass(PersonUpsertRequestedV1.class);
            verify(peopleEventPublisher).requestPersonUpsert(command.capture());
            assertThat(command.getValue().workPhones()).isEmpty();
            assertThat(command.getValue().primaryEmail()).isNull();
        }

        @Test
        void rejectsATerminationDateBeforeTheHireDate() {
            CreateEmployeeRequest request = createRequest();
            request.setTerminationDate(LocalDate.of(2026, 1, 14));

            assertThatThrownBy(() -> service.createEmployee(request))
                    .isInstanceOf(SemanticValidationException.class)
                    .hasMessageContaining("terminationDate");

            verifyNoInteractions(peopleEventPublisher);
        }

        @Test
        void rejectsADuplicateEmployeeNumberUnderTheStrictPolicy() {
            when(employeeRepository.existsByEmployeeNumberIgnoreCase("EMP-0001"))
                    .thenReturn(true);

            CreateEmployeeRequest request = createRequest();

            assertThatThrownBy(() -> service.createEmployee(request))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("STRICT");

            verify(employeeRepository, never()).save(any());
        }

        @Test
        void rejectsADuplicatePrimaryEmailUnderTheStrictPolicy() {
            CreateEmployeeRequest request = createRequest();
            request.setContactInfo(contact("jane@example.com", null, null));
            when(extPersonReplicaRepository.findByPrimaryEmailIgnoreCase("jane@example.com"))
                    .thenReturn(List.of(replica()));

            assertThatThrownBy(() -> service.createEmployee(request)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void rejectsADuplicatePhoneUnderTheStrictPolicy() {
            CreateEmployeeRequest request = createRequest();
            request.setContactInfo(contact(null, null, "555-0199"));
            when(extPersonReplicaRepository.findByPrimaryPhoneOrSecondaryPhone("555-0199", "555-0199"))
                    .thenReturn(List.of(replica()));

            assertThatThrownBy(() -> service.createEmployee(request)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void acceptsADuplicateWithAWarningUnderTheBalancedPolicy() {
            CreateEmployeeRequest request = createRequest();
            request.setDuplicatePolicy(DuplicatePolicy.BALANCED);
            when(employeeRepository.existsByEmployeeNumberIgnoreCase("EMP-0001"))
                    .thenReturn(true);

            EmployeeProfileDto profile = service.createEmployee(request);

            assertThat(profile.getWarnings()).anyMatch(warning -> warning.contains("BALANCED"));
            verify(employeeRepository).save(any(Employee.class));
        }

        @Test
        void warnsOnAnAmbiguousNameMatchUnderTheBalancedPolicy() {
            CreateEmployeeRequest request = createRequest();
            request.setDuplicatePolicy(DuplicatePolicy.BALANCED);
            when(extPersonReplicaRepository.findByLastNameIgnoreCase("Smith")).thenReturn(List.of(replica()));

            EmployeeProfileDto profile = service.createEmployee(request);

            assertThat(profile.getWarnings()).containsExactly("Ambiguous duplicate match detected by name similarity");
        }

        @Test
        void doesNotTreatABlankNameAsAnAmbiguousMatch() {
            CreateEmployeeRequest request = createRequest();
            request.setDuplicatePolicy(DuplicatePolicy.BALANCED);
            request.setFirstName("  ");

            assertThat(service.createEmployee(request).getWarnings()).isEmpty();
            verifyNoInteractions(extPersonReplicaRepository);
        }

        @Test
        void treatsANullDuplicatePolicyAsStrict() {
            CreateEmployeeRequest request = createRequest();
            request.setDuplicatePolicy(null);
            when(employeeRepository.existsByEmployeeNumberIgnoreCase("EMP-0001"))
                    .thenReturn(true);

            assertThatThrownBy(() -> service.createEmployee(request)).isInstanceOf(IllegalStateException.class);
        }

        @Test
        void roundTripsAJobRoleFromTheTenantsList() {
            when(jobRoleRepository.existsById(JOB_ROLE_ID)).thenReturn(true);
            when(jobRoleRepository.findById(JOB_ROLE_ID)).thenReturn(Optional.of(jobRole()));

            CreateEmployeeRequest request = createRequest();
            request.setJobRoleId(JOB_ROLE_ID);

            EmployeeProfileDto profile = service.createEmployee(request);

            ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
            verify(employeeRepository).save(saved.capture());
            assertThat(saved.getValue().getJobRoleId()).isEqualTo(JOB_ROLE_ID);
            assertThat(profile.getJobRole()).isNotNull();
            assertThat(profile.getJobRole().getId()).isEqualTo(JOB_ROLE_ID);
            assertThat(profile.getJobRole().getCode()).isEqualTo("LEAD_TECH");
            assertThat(profile.getJobRole().getName()).isEqualTo("Lead Technician");
        }

        @Test
        void aNullJobRoleIsAcceptedAndReadBackAsNull() {
            EmployeeProfileDto profile = service.createEmployee(createRequest());

            assertThat(profile.getJobRole()).isNull();
            verifyNoInteractions(jobRoleRepository);
        }

        @Test
        void rejectsAJobRoleIdThatIsNotOnTheTenantsList() {
            when(jobRoleRepository.existsById(JOB_ROLE_ID)).thenReturn(false);

            CreateEmployeeRequest request = createRequest();
            request.setJobRoleId(JOB_ROLE_ID);

            assertThatThrownBy(() -> service.createEmployee(request)).isInstanceOf(NotFoundException.class);
            verify(employeeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("getEmployee")
    class GetEmployee {

        @Test
        void servesIdentityFromTheReplicaAndEmploymentFromTheLocalRow() {
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(replica()));
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));

            EmployeeProfileDto profile = service.getEmployee(PERSON_ID);

            assertThat(profile.getFirstName()).isEqualTo("Jane");
            assertThat(profile.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
            assertThat(profile.getContactInfo().getPrimaryEmail()).isEqualTo("jane@example.com");
            assertThat(profile.getCreatedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
            assertThat(profile.getUpdatedAt()).isEqualTo(Instant.parse("2026-02-01T00:00:00Z"));
        }

        @Test
        void servesAnEmploymentRowWhoseReplicaHasNotCaughtUp() {
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.empty());
            Employee employee = employee(EmployeeStatus.ACTIVE);
            employee.setCreatedAt(NOW);
            employee.setUpdatedAt(NOW);
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee));

            EmployeeProfileDto profile = service.getEmployee(PERSON_ID);

            assertThat(profile.getFirstName()).isNull();
            assertThat(profile.getContactInfo()).isNull();
            assertThat(profile.getCreatedAt()).isEqualTo(NOW);
            assertThat(profile.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        void servesAReplicaRowWithNoEmploymentRecordYet() {
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(replica()));
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());

            EmployeeProfileDto profile = service.getEmployee(PERSON_ID);

            assertThat(profile.getEmployeeNumber()).isNull();
            assertThat(profile.getStatus()).isNull();
            assertThat(profile.getFirstName()).isEqualTo("Jane");
        }

        @Test
        void omitsContactInfoWhenTheReplicaCarriesNoContactValues() {
            ExtPersonReplica person = replica();
            person.setPrimaryEmail("  ");
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(person));
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());

            assertThat(service.getEmployee(PERSON_ID).getContactInfo()).isNull();
        }

        @Test
        void failsWhenNeitherTheReplicaNorAnEmploymentRowExists() {
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.empty());
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getEmployee(PERSON_ID)).isInstanceOf(PersonNotFoundException.class);
        }

        @Test
        void mapsTheJobRoleFromTheTenantsList() {
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(replica()));
            Employee employee = employee(EmployeeStatus.ACTIVE);
            employee.setJobRoleId(JOB_ROLE_ID);
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee));
            when(jobRoleRepository.findById(JOB_ROLE_ID)).thenReturn(Optional.of(jobRole()));

            EmployeeProfileDto profile = service.getEmployee(PERSON_ID);

            assertThat(profile.getJobRole()).isNotNull();
            assertThat(profile.getJobRole().getId()).isEqualTo(JOB_ROLE_ID);
            assertThat(profile.getJobRole().getName()).isEqualTo("Lead Technician");
        }

        @Test
        void anEmployeeWithNoJobRoleReadsBackNull() {
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(replica()));
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));

            assertThat(service.getEmployee(PERSON_ID).getJobRole()).isNull();
            verifyNoInteractions(jobRoleRepository);
        }
    }

    @Nested
    @DisplayName("updateEmployee")
    class UpdateEmployee {

        @Test
        void restampsStatusEffectiveAtOnlyWhenTheStatusChanges() {
            Employee existing = employee(EmployeeStatus.ACTIVE);
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(existing));

            UpdateEmployeeRequest request = updateRequest();
            request.setStatus(EmployeeStatus.ON_LEAVE);

            service.updateEmployee(PERSON_ID, request);

            ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
            verify(employeeRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(EmployeeStatus.ON_LEAVE);
            assertThat(saved.getValue().getStatusEffectiveAt()).isEqualTo(NOW);
        }

        @Test
        void leavesStatusEffectiveAtAloneWhenTheStatusIsUnchanged() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));

            service.updateEmployee(PERSON_ID, updateRequest());

            ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
            verify(employeeRepository).save(saved.capture());
            assertThat(saved.getValue().getStatusEffectiveAt()).isEqualTo(Instant.parse("2026-01-15T00:00:00Z"));
        }

        @Test
        void createsTheEmploymentRowWhenOnlyTheReplicaKnowsThePerson() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());
            when(extPersonReplicaRepository.existsById(PERSON_ID)).thenReturn(true);

            EmployeeProfileDto profile = service.updateEmployee(PERSON_ID, updateRequest());

            ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
            verify(employeeRepository).save(saved.capture());
            assertThat(saved.getValue().getPersonId()).isEqualTo(PERSON_ID);
            assertThat(profile.getId()).isEqualTo(PERSON_ID);
        }

        @Test
        void failsWhenNeitherTheReplicaNorAnEmploymentRowExists() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());
            when(extPersonReplicaRepository.existsById(PERSON_ID)).thenReturn(false);

            UpdateEmployeeRequest request = updateRequest();

            assertThatThrownBy(() -> service.updateEmployee(PERSON_ID, request))
                    .isInstanceOf(PersonNotFoundException.class);
        }

        @Test
        void rejectsATerminationDateBeforeTheHireDate() {
            UpdateEmployeeRequest request = updateRequest();
            request.setTerminationDate(LocalDate.of(2020, 1, 1));

            assertThatThrownBy(() -> service.updateEmployee(PERSON_ID, request))
                    .isInstanceOf(SemanticValidationException.class);
        }

        @Test
        void ignoresTheEmployeesOwnRowWhenScanningForDuplicates() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
            when(employeeRepository.existsByEmployeeNumberIgnoreCaseAndPersonIdNot("EMP-0001", PERSON_ID))
                    .thenReturn(false);
            // The replica row for this very person must not count as somebody else's email.
            when(extPersonReplicaRepository.findByPrimaryEmailIgnoreCase("jane@example.com"))
                    .thenReturn(List.of(replica()));

            UpdateEmployeeRequest request = updateRequest();
            request.setContactInfo(contact("jane@example.com", null, null));

            assertThat(service.updateEmployee(PERSON_ID, request).getWarnings()).isEmpty();
        }

        @Test
        void rejectsAnEmployeeNumberAlreadyHeldBySomeoneElse() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
            when(employeeRepository.existsByEmployeeNumberIgnoreCaseAndPersonIdNot("EMP-0001", PERSON_ID))
                    .thenReturn(true);

            UpdateEmployeeRequest request = updateRequest();

            assertThatThrownBy(() -> service.updateEmployee(PERSON_ID, request))
                    .isInstanceOf(IllegalStateException.class);
        }

        @Test
        void countsAnotherPersonsNameAsAnAmbiguousMatch() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
            ExtPersonReplica other = replica();
            other.setPersonId(OTHER_PERSON_ID);
            when(extPersonReplicaRepository.findByLastNameIgnoreCase("Smith")).thenReturn(List.of(other));

            UpdateEmployeeRequest request = updateRequest();
            request.setDuplicatePolicy(DuplicatePolicy.BALANCED);

            assertThat(service.updateEmployee(PERSON_ID, request).getWarnings())
                    .containsExactly("Ambiguous duplicate match detected by name similarity");
        }

        @Test
        void roundTripsAJobRoleChange() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
            when(jobRoleRepository.existsById(JOB_ROLE_ID)).thenReturn(true);
            when(jobRoleRepository.findById(JOB_ROLE_ID)).thenReturn(Optional.of(jobRole()));

            UpdateEmployeeRequest request = updateRequest();
            request.setJobRoleId(JOB_ROLE_ID);

            EmployeeProfileDto profile = service.updateEmployee(PERSON_ID, request);

            ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
            verify(employeeRepository).save(saved.capture());
            assertThat(saved.getValue().getJobRoleId()).isEqualTo(JOB_ROLE_ID);
            assertThat(profile.getJobRole().getId()).isEqualTo(JOB_ROLE_ID);
        }

        @Test
        void omittingTheJobRoleClearsAPreviouslySetOne() {
            Employee existing = employee(EmployeeStatus.ACTIVE);
            existing.setJobRoleId(JOB_ROLE_ID);
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(existing));

            EmployeeProfileDto profile = service.updateEmployee(PERSON_ID, updateRequest());

            ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
            verify(employeeRepository).save(saved.capture());
            assertThat(saved.getValue().getJobRoleId()).isNull();
            assertThat(profile.getJobRole()).isNull();
        }

        @Test
        void rejectsAJobRoleIdThatIsNotOnTheTenantsList() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
            when(jobRoleRepository.existsById(JOB_ROLE_ID)).thenReturn(false);

            UpdateEmployeeRequest request = updateRequest();
            request.setJobRoleId(JOB_ROLE_ID);

            assertThatThrownBy(() -> service.updateEmployee(PERSON_ID, request)).isInstanceOf(NotFoundException.class);
            verify(employeeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("disableEmployee")
    class DisableEmployee {

        private DisableEmployeeRequestDto disableRequest(AssignmentTerminationPolicy policy) {
            DisableEmployeeRequestDto request = new DisableEmployeeRequestDto();
            request.setDisableReason("Voluntary resignation");
            request.setAssignmentPolicy(policy);
            request.setAssignmentEndDate(LocalDate.of(2026, 3, 31));
            return request;
        }

        @Test
        void movesAnActiveEmployeeToDisabledAndPublishesTheFact() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(replica()));

            EmployeeProfileDto profile =
                    service.disableEmployee(PERSON_ID, disableRequest(AssignmentTerminationPolicy.IMMEDIATE));

            ArgumentCaptor<Employee> saved = ArgumentCaptor.forClass(Employee.class);
            verify(employeeRepository).save(saved.capture());
            assertThat(saved.getValue().getStatus()).isEqualTo(EmployeeStatus.DISABLED);
            assertThat(saved.getValue().getStatusEffectiveAt()).isEqualTo(NOW);
            verify(peopleEventPublisher).publishEmployeeUpdated(saved.getValue());
            verifyNoInteractions(offboardingRetryRepository);
            assertThat(profile.getStatus()).isEqualTo(EmployeeStatus.DISABLED);
            assertThat(profile.getFirstName()).isEqualTo("Jane");
        }

        @Test
        void acceptsTheGracePeriodPolicy() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.empty());

            EmployeeProfileDto profile =
                    service.disableEmployee(PERSON_ID, disableRequest(AssignmentTerminationPolicy.GRACE_PERIOD));

            assertThat(profile.getStatus()).isEqualTo(EmployeeStatus.DISABLED);
            verifyNoInteractions(offboardingRetryRepository);
        }

        @Test
        void queuesARetryWhenTheDownstreamOffboardingActionFails() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.of(employee(EmployeeStatus.ACTIVE)));
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.empty());

            // The downstream action reads the end date; a failure there must not undo the disable.
            DisableEmployeeRequestDto request = new DisableEmployeeRequestDto() {
                @Override
                public LocalDate getAssignmentEndDate() {
                    throw new IllegalStateException("assignment service unavailable");
                }
            };
            request.setAssignmentPolicy(AssignmentTerminationPolicy.GRACE_PERIOD);
            request.setDisableReason("Voluntary resignation");

            EmployeeProfileDto profile = service.disableEmployee(PERSON_ID, request);

            assertThat(profile.getStatus()).isEqualTo(EmployeeStatus.DISABLED);
            ArgumentCaptor<EmployeeOffboardingRetry> retry = ArgumentCaptor.forClass(EmployeeOffboardingRetry.class);
            verify(offboardingRetryRepository).save(retry.capture());
            assertThat(retry.getValue().getEmployeeId()).isEqualTo(PERSON_ID);
            assertThat(retry.getValue().getAssignmentPolicy()).isEqualTo(AssignmentTerminationPolicy.GRACE_PERIOD);
            assertThat(retry.getValue().getFailureReason()).contains("assignment service unavailable");
            assertThat(retry.getValue().getAttempts()).isZero();
            assertThat(retry.getValue().getNextAttemptAt()).isEqualTo(NOW.plusSeconds(300));
            assertThat(retry.getValue().getActorId()).isEqualTo("system");
        }

        @Test
        void failsWhenTheEmployeeDoesNotExist() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());
            DisableEmployeeRequestDto request = disableRequest(AssignmentTerminationPolicy.IMMEDIATE);

            assertThatThrownBy(() -> service.disableEmployee(PERSON_ID, request))
                    .isInstanceOf(PersonNotFoundException.class);
        }

        @Test
        void rejectsAnAlreadyDisabledEmployee() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employee(EmployeeStatus.DISABLED)));
            DisableEmployeeRequestDto request = disableRequest(AssignmentTerminationPolicy.IMMEDIATE);

            assertThatThrownBy(() -> service.disableEmployee(PERSON_ID, request))
                    .isInstanceOf(ResourceStateConflictException.class)
                    .hasMessageContaining("already DISABLED or TERMINATED");
        }

        @Test
        void rejectsATerminatedEmployee() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employee(EmployeeStatus.TERMINATED)));
            DisableEmployeeRequestDto request = disableRequest(AssignmentTerminationPolicy.IMMEDIATE);

            assertThatThrownBy(() -> service.disableEmployee(PERSON_ID, request))
                    .isInstanceOf(ResourceStateConflictException.class);
        }

        @Test
        void rejectsAnEmployeeThatIsNeitherActiveNorAlreadyDisabled() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employee(EmployeeStatus.SUSPENDED)));
            DisableEmployeeRequestDto request = disableRequest(AssignmentTerminationPolicy.IMMEDIATE);

            assertThatThrownBy(() -> service.disableEmployee(PERSON_ID, request))
                    .isInstanceOf(ResourceStateConflictException.class)
                    .hasMessageContaining("Only ACTIVE employees can be disabled");
        }
    }

    @Nested
    @DisplayName("enableEmployee")
    class EnableEmployee {

        private static final Instant CURRENT_UPDATED_AT = Instant.parse("2026-02-15T10:00:00Z");

        private Employee employeeWithUpdatedAt(EmployeeStatus status, Instant updatedAt) {
            return Employee.builder()
                    .id(UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4aaa"))
                    .personId(PERSON_ID)
                    .employeeNumber("EMP-0001")
                    .status(status)
                    .hireDate(LocalDate.of(2026, 1, 15))
                    .statusEffectiveAt(Instant.parse("2026-01-15T00:00:00Z"))
                    .updatedAt(updatedAt)
                    .build();
        }

        private EnableEmployeeRequestDto enableRequest(Instant updatedAt) {
            EnableEmployeeRequestDto request = new EnableEmployeeRequestDto();
            request.setUpdatedAt(updatedAt);
            return request;
        }

        @Test
        void movesADisabledEmployeeToActiveAndPublishesTheFact() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employeeWithUpdatedAt(EmployeeStatus.DISABLED, CURRENT_UPDATED_AT)));
            // #2158 finding C: the reactivation write is now this one atomic conditional UPDATE,
            // not a load-compare-then-save() -- see EmployeeRepository#reactivateIfDisabledAndTokenMatches.
            when(employeeRepository.reactivateIfDisabledAndTokenMatches(PERSON_ID, CURRENT_UPDATED_AT, NOW))
                    .thenReturn(1);
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(replica()));

            EmployeeProfileDto profile = service.enableEmployee(PERSON_ID, enableRequest(CURRENT_UPDATED_AT));

            verify(employeeRepository).reactivateIfDisabledAndTokenMatches(PERSON_ID, CURRENT_UPDATED_AT, NOW);
            // No redundant second write: the conditional UPDATE above already persisted the new
            // status/statusEffectiveAt/updatedAt in one statement.
            verify(employeeRepository, never()).save(any());
            ArgumentCaptor<Employee> published = ArgumentCaptor.forClass(Employee.class);
            verify(peopleEventPublisher).publishEmployeeUpdated(published.capture());
            assertThat(published.getValue().getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
            assertThat(published.getValue().getStatusEffectiveAt()).isEqualTo(NOW);
            assertThat(published.getValue().getUpdatedAt()).isEqualTo(NOW);
            assertThat(profile.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);
            // profile.updatedAt comes from the identity replica (person.personUpdatedAt) when a
            // replica row exists, not from the employee row -- see profileFromReplica -- so it is
            // NOT asserted against `now` here; published.getValue().getUpdatedAt() above already
            // pins the employee-side value the atomic update wrote.
            assertThat(profile.getFirstName()).isEqualTo("Jane");
        }

        /**
         * #2158 finding C, the closest a mocked-repository test can get to the actual race: two
         * concurrent enableEmployee calls that both read the same DISABLED row with the same
         * updatedAt token both pass every in-memory guard (TERMINATED/ON_LEAVE/SUSPENDED/ACTIVE
         * are all ruled out identically on both reads), so nothing in the guard checks above can
         * tell them apart -- only the atomic conditional UPDATE can, since a real database would
         * let exactly one of the two racing UPDATEs still find a matching row. This pins that the
         * service trusts that return value rather than the in-memory guards: stubbing the second
         * call to reactivateIfDisabledAndTokenMatches with 0 (what the DB would return for the
         * loser of the race) must 409 the second attempt even though its own guard checks, run
         * against the same stale DISABLED snapshot, all pass.
         */
        @Test
        void twoSequentialAttemptsWithTheSameTokenCannotBothSucceed() {
            // A fresh Employee instance per call (thenAnswer, not thenReturn of one shared
            // instance): a real repository read returns an independent object each time, and the
            // first call's in-memory field mutations (see enableEmployee) must not leak into what
            // the second call reads -- only the stubbed reactivateIfDisabledAndTokenMatches below
            // should be what tells the two calls apart, exactly as the real atomic UPDATE would.
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenAnswer(invocation ->
                            Optional.of(employeeWithUpdatedAt(EmployeeStatus.DISABLED, CURRENT_UPDATED_AT)));
            when(extPersonReplicaRepository.findById(PERSON_ID)).thenReturn(Optional.of(replica()));
            when(employeeRepository.reactivateIfDisabledAndTokenMatches(PERSON_ID, CURRENT_UPDATED_AT, NOW))
                    .thenReturn(1)
                    .thenReturn(0);

            EmployeeProfileDto first = service.enableEmployee(PERSON_ID, enableRequest(CURRENT_UPDATED_AT));
            assertThat(first.getStatus()).isEqualTo(EmployeeStatus.ACTIVE);

            assertThatThrownBy(() -> service.enableEmployee(PERSON_ID, enableRequest(CURRENT_UPDATED_AT)))
                    .isInstanceOf(ResourceStateConflictException.class)
                    .hasMessageContaining("updatedAt");

            verify(employeeRepository, times(2))
                    .reactivateIfDisabledAndTokenMatches(PERSON_ID, CURRENT_UPDATED_AT, NOW);
            verify(peopleEventPublisher, times(1)).publishEmployeeUpdated(any());
        }

        @Test
        void failsWhenTheEmployeeDoesNotExist() {
            when(employeeRepository.findByPersonId(PERSON_ID)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.enableEmployee(PERSON_ID, enableRequest(CURRENT_UPDATED_AT)))
                    .isInstanceOf(PersonNotFoundException.class);
        }

        @Test
        void rejectsATerminatedEmployee() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employeeWithUpdatedAt(EmployeeStatus.TERMINATED, CURRENT_UPDATED_AT)));

            assertThatThrownBy(() -> service.enableEmployee(PERSON_ID, enableRequest(CURRENT_UPDATED_AT)))
                    .isInstanceOf(ResourceStateConflictException.class)
                    .hasMessageContaining("TERMINATED");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        void rejectsAnOnLeaveEmployeePointingAtTheProfileUpdate() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employeeWithUpdatedAt(EmployeeStatus.ON_LEAVE, CURRENT_UPDATED_AT)));

            assertThatThrownBy(() -> service.enableEmployee(PERSON_ID, enableRequest(CURRENT_UPDATED_AT)))
                    .isInstanceOf(ResourceStateConflictException.class)
                    .hasMessageContaining("updateEmployee");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        void rejectsASuspendedEmployeePointingAtTheProfileUpdate() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employeeWithUpdatedAt(EmployeeStatus.SUSPENDED, CURRENT_UPDATED_AT)));

            assertThatThrownBy(() -> service.enableEmployee(PERSON_ID, enableRequest(CURRENT_UPDATED_AT)))
                    .isInstanceOf(ResourceStateConflictException.class)
                    .hasMessageContaining("updateEmployee");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        void rejectsAnAlreadyActiveEmployee() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employeeWithUpdatedAt(EmployeeStatus.ACTIVE, CURRENT_UPDATED_AT)));

            assertThatThrownBy(() -> service.enableEmployee(PERSON_ID, enableRequest(CURRENT_UPDATED_AT)))
                    .isInstanceOf(ResourceStateConflictException.class)
                    .hasMessageContaining("Only DISABLED employees can be enabled");
            verify(employeeRepository, never()).save(any());
        }

        @Test
        void rejectsAStaleConcurrencyToken() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employeeWithUpdatedAt(EmployeeStatus.DISABLED, CURRENT_UPDATED_AT)));

            Instant staleToken = CURRENT_UPDATED_AT.minusSeconds(60);

            assertThatThrownBy(() -> service.enableEmployee(PERSON_ID, enableRequest(staleToken)))
                    .isInstanceOf(ResourceStateConflictException.class)
                    .hasMessageContaining("updatedAt");
            verify(employeeRepository, never()).save(any());
        }
    }

    @Nested
    @DisplayName("searchEmployees")
    class SearchEmployees {

        private static final UUID SMITH_PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b01");
        private static final UUID DOE_PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b02");
        private static final UUID NO_REPLICA_PERSON_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4b03");

        private Employee employeeRow(UUID personId, String employeeNumber, EmployeeStatus status) {
            return Employee.builder()
                    .id(UUID.randomUUID())
                    .personId(personId)
                    .employeeNumber(employeeNumber)
                    .status(status)
                    .build();
        }

        private ExtPersonReplica replicaRow(UUID personId, String firstName, String lastName, String preferredName) {
            ExtPersonReplica person = new ExtPersonReplica();
            person.setPersonId(personId);
            person.setFirstName(firstName);
            person.setLastName(lastName);
            person.setPreferredName(preferredName);
            return person;
        }

        private void givenTheDirectory() {
            Employee smith = employeeRow(SMITH_PERSON_ID, "EMP-0001", EmployeeStatus.ACTIVE);
            Employee doe = employeeRow(DOE_PERSON_ID, "EMP-0002", EmployeeStatus.ON_LEAVE);
            Employee noReplica = employeeRow(NO_REPLICA_PERSON_ID, "EMP-0003", EmployeeStatus.ACTIVE);
            when(employeeRepository.findAll()).thenReturn(List.of(smith, doe, noReplica));

            when(extPersonReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(
                            replicaRow(SMITH_PERSON_ID, "Jane", "Smith", "Janie"),
                            replicaRow(DOE_PERSON_ID, "John", "Doe", null)));
        }

        /**
         * Ten employees, lastName Adams..Jones alphabetically, with DISABLED employees scattered
         * at non-adjacent positions (Baker, Davis, Foster, Hale — 2nd, 4th, 6th, 8th) rather than
         * bunched at the front or back. A dataset this shape is what makes the #2158 regression
         * visible: filtering only the current page (the bug) would return whichever disabled
         * rows happened to land on that page, not the whole disabled population, and would look
         * "kind of right" on a front-loaded or back-loaded fixture. The ordering also spans more
         * than one page at every size used below (2, 3, or 5), which is what a sort or filter
         * bug that "restarts" per page needs to be caught.
         */
        private void givenALargeDirectory() {
            String[] lastNames = {
                "Adams", "Baker", "Cole", "Davis", "Evans", "Foster", "Grant", "Hale", "Irwin", "Jones"
            };
            EmployeeStatus[] statuses = {
                EmployeeStatus.ACTIVE,
                EmployeeStatus.DISABLED,
                EmployeeStatus.ACTIVE,
                EmployeeStatus.DISABLED,
                EmployeeStatus.ACTIVE,
                EmployeeStatus.DISABLED,
                EmployeeStatus.ACTIVE,
                EmployeeStatus.DISABLED,
                EmployeeStatus.ACTIVE,
                EmployeeStatus.ACTIVE
            };
            List<Employee> employees = new ArrayList<>();
            List<ExtPersonReplica> replicas = new ArrayList<>();
            for (int i = 0; i < lastNames.length; i++) {
                UUID personId = UUID.randomUUID();
                employees.add(employeeRow(personId, "EMP-1%03d".formatted(i), statuses[i]));
                replicas.add(replicaRow(personId, "First" + i, lastNames[i], null));
            }
            when(employeeRepository.findAll()).thenReturn(employees);
            when(extPersonReplicaRepository.findByPersonIdIn(any())).thenReturn(replicas);
        }

        @Test
        void aBlankQueryListsEveryEmployee() {
            givenTheDirectory();

            PagedResponse<EmployeeSummaryDto> page = service.searchEmployees("  ", null, null, 0, 20, null);

            assertThat(page.items()).hasSize(3);
            assertThat(page.totalElements()).isEqualTo(3);
            assertThat(page.totalPages()).isEqualTo(1);
        }

        @Test
        void aNullQueryListsEveryEmployee() {
            givenTheDirectory();

            assertThat(service.searchEmployees(null, null, null, 0, 20, null).items())
                    .hasSize(3);
        }

        @Test
        void matchesByLastName() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees("smith", null, null, 0, 20, null).items();

            assertThat(results)
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0001");
        }

        @Test
        void matchesByPreferredName() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees("Janie", null, null, 0, 20, null).items();

            assertThat(results)
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0001");
        }

        @Test
        void matchesByEmployeeNumber() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees("0002", null, null, 0, 20, null).items();

            assertThat(results)
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0002");
        }

        @Test
        void matchingIsCaseInsensitive() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees("DOE", null, null, 0, 20, null).items();

            assertThat(results)
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0002");
        }

        @Test
        void anEmployeeWithNoReplicaRowIsStillFoundByNumberAndCarriesNullNames() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees("EMP-0003", null, null, 0, 20, null).items();

            assertThat(results).hasSize(1);
            EmployeeSummaryDto found = results.get(0);
            assertThat(found.getEmployeeNumber()).isEqualTo("EMP-0003");
            assertThat(found.getFirstName()).isNull();
            assertThat(found.getLastName()).isNull();
            assertThat(found.isActive()).isTrue();
        }

        @Test
        void activeMirrorsTheEmployeeStatus() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees(null, null, null, 0, 20, null).items();

            assertThat(results)
                    .filteredOn(dto -> dto.getEmployeeNumber().equals("EMP-0001"))
                    .extracting(EmployeeSummaryDto::isActive)
                    .containsExactly(true);
            assertThat(results)
                    .filteredOn(dto -> dto.getEmployeeNumber().equals("EMP-0002"))
                    .extracting(EmployeeSummaryDto::isActive)
                    .containsExactly(false);
        }

        @Test
        void pagesTheSortedResultsAndReportsCorrectTotals() {
            givenTheDirectory();

            // lastName order: Doe, Smith, then the no-replica row (null last name sorts last).
            PagedResponse<EmployeeSummaryDto> firstPage = service.searchEmployees(null, null, null, 0, 2, null);
            assertThat(firstPage.items())
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0002", "EMP-0001");
            assertThat(firstPage.totalElements()).isEqualTo(3);
            assertThat(firstPage.totalPages()).isEqualTo(2);

            PagedResponse<EmployeeSummaryDto> secondPage = service.searchEmployees(null, null, null, 1, 2, null);
            assertThat(secondPage.items())
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0003");
            assertThat(secondPage.totalElements()).isEqualTo(3);
            assertThat(secondPage.totalPages()).isEqualTo(2);
        }

        @Test
        void anOutOfRangePageReturnsEmptyItemsWithCorrectTotals() {
            givenTheDirectory();

            PagedResponse<EmployeeSummaryDto> page = service.searchEmployees(null, null, null, 5, 20, null);

            assertThat(page.items()).isEmpty();
            assertThat(page.totalElements()).isEqualTo(3);
            assertThat(page.totalPages()).isEqualTo(1);
            assertThat(page.page()).isEqualTo(5);
            assertThat(page.size()).isEqualTo(20);
        }

        /**
         * The issue's headline acceptance criterion: totalElements for a status filter must be
         * the full matching count (4, across the whole 10-row directory), not the count within
         * the requested page window (2, for size=2). Asserting only the item count would pass
         * even with the pre-fix in-page-only filter, since a page of 2 disabled rows out of 2
         * requested still "looks" like 2 of 2 — the totals field is what exposes the bug.
         */
        @Test
        void statusFilterAppliesAcrossTheWholeDirectoryNotJustThePage() {
            givenALargeDirectory();

            PagedResponse<EmployeeSummaryDto> page =
                    service.searchEmployees(null, List.of(EmployeeStatus.DISABLED), null, 0, 2, null);

            assertThat(page.items()).extracting(EmployeeSummaryDto::getLastName).containsExactly("Baker", "Davis");
            assertThat(page.items()).allMatch(dto -> "DISABLED".equals(dto.getStatus()));
            assertThat(page.totalElements()).isEqualTo(4);
            assertThat(page.totalPages()).isEqualTo(2);

            PagedResponse<EmployeeSummaryDto> secondPage =
                    service.searchEmployees(null, List.of(EmployeeStatus.DISABLED), null, 1, 2, null);
            assertThat(secondPage.items())
                    .extracting(EmployeeSummaryDto::getLastName)
                    .containsExactly("Foster", "Hale");
            assertThat(secondPage.totalElements()).isEqualTo(4);
        }

        @Test
        void repeatedStatusParamsCombineWithOrSemantics() {
            givenALargeDirectory();

            PagedResponse<EmployeeSummaryDto> page = service.searchEmployees(
                    null, List.of(EmployeeStatus.ACTIVE, EmployeeStatus.DISABLED), null, 0, 20, null);

            // Every row in the fixture is ACTIVE or DISABLED, so requesting both is equivalent
            // to no status filter at all — proving the two statuses were OR'd together rather
            // than (wrongly) intersected, which would return nothing.
            assertThat(page.totalElements()).isEqualTo(10);
        }

        @Test
        void aNullOrEmptyStatusListAppliesNoFilter() {
            givenALargeDirectory();

            assertThat(service.searchEmployees(null, null, null, 0, 20, null).totalElements())
                    .isEqualTo(10);
            assertThat(service.searchEmployees(null, List.of(), null, 0, 20, null)
                            .totalElements())
                    .isEqualTo(10);
        }

        /**
         * lastName,desc must order the WHOLE result set, not restart per page: page 0's last row
         * (Foster) must sort strictly after page 1's first row (Evans) under descending order —
         * i.e. continuing to descend across the page boundary rather than each page
         * independently starting from Z.
         */
        @Test
        void descendingSortOrdersAcrossTheWholeResultSetNotPerPage() {
            givenALargeDirectory();

            PagedResponse<EmployeeSummaryDto> firstPage =
                    service.searchEmployees(null, null, "lastName,desc", 0, 5, null);
            PagedResponse<EmployeeSummaryDto> secondPage =
                    service.searchEmployees(null, null, "lastName,desc", 1, 5, null);

            assertThat(firstPage.items())
                    .extracting(EmployeeSummaryDto::getLastName)
                    .containsExactly("Jones", "Irwin", "Hale", "Grant", "Foster");
            assertThat(secondPage.items())
                    .extracting(EmployeeSummaryDto::getLastName)
                    .containsExactly("Evans", "Davis", "Cole", "Baker", "Adams");

            String lastOfFirstPage =
                    firstPage.items().get(firstPage.items().size() - 1).getLastName();
            String firstOfSecondPage = secondPage.items().get(0).getLastName();
            assertThat(lastOfFirstPage.compareToIgnoreCase(firstOfSecondPage)).isGreaterThan(0);
        }

        @Test
        void ascendingSortIsTheDefaultAndMatchesLegacyOrdering() {
            givenALargeDirectory();

            PagedResponse<EmployeeSummaryDto> withoutSort = service.searchEmployees(null, null, null, 0, 10, null);
            PagedResponse<EmployeeSummaryDto> withExplicitAscSort =
                    service.searchEmployees(null, null, "lastName,asc", 0, 10, null);

            assertThat(withoutSort.items())
                    .extracting(EmployeeSummaryDto::getLastName)
                    .containsExactly(
                            "Adams", "Baker", "Cole", "Davis", "Evans", "Foster", "Grant", "Hale", "Irwin", "Jones");
            assertThat(withExplicitAscSort.items())
                    .extracting(EmployeeSummaryDto::getLastName)
                    .containsExactlyElementsOf(withoutSort.items().stream()
                            .map(EmployeeSummaryDto::getLastName)
                            .toList());
        }

        @Test
        void anUnsupportedSortFieldRaisesAValidationException() {
            givenALargeDirectory();

            assertThatThrownBy(() -> service.searchEmployees(null, null, "employeeNumber,asc", 0, 20, null))
                    .isInstanceOf(RequestValidationException.class);
        }

        @Test
        void anUnsupportedSortDirectionRaisesAValidationException() {
            givenALargeDirectory();

            assertThatThrownBy(() -> service.searchEmployees(null, null, "lastName,sideways", 0, 20, null))
                    .isInstanceOf(RequestValidationException.class);
        }

        /**
         * A caller that only ever passed q/page/size (the pre-#2158 contract) must see identical
         * results after this change: no status filter, default lastName-ascending order.
         */
        @Test
        void anExistingCallerPassingOnlyQPageAndSizeSeesUnchangedBehaviour() {
            givenALargeDirectory();

            PagedResponse<EmployeeSummaryDto> legacyStyleCall =
                    service.searchEmployees("First", null, null, 0, 10, null);

            assertThat(legacyStyleCall.items())
                    .extracting(EmployeeSummaryDto::getLastName)
                    .containsExactly(
                            "Adams", "Baker", "Cole", "Davis", "Evans", "Foster", "Grant", "Hale", "Irwin", "Jones");
            assertThat(legacyStyleCall.totalElements()).isEqualTo(10);
        }
    }

    /**
     * durion#2158, corrected: the status histogram for the register's stat tiles, now its own
     * {@code employeeStatusCounts} call rather than folded into {@code searchEmployees}'s
     * response (finding A) -- and keyed by {@code String} with an explicit bucket for a
     * null-status row rather than silently dropping it (finding B).
     */
    @Nested
    @DisplayName("employeeStatusCounts")
    class EmployeeStatusCounts {

        private Employee employeeRow(UUID personId, String employeeNumber, EmployeeStatus status) {
            return Employee.builder()
                    .id(UUID.randomUUID())
                    .personId(personId)
                    .employeeNumber(employeeNumber)
                    .status(status)
                    .build();
        }

        private ExtPersonReplica replicaRow(UUID personId, String firstName, String lastName) {
            ExtPersonReplica person = new ExtPersonReplica();
            person.setPersonId(personId);
            person.setFirstName(firstName);
            person.setLastName(lastName);
            return person;
        }

        /**
         * Ten employees, ACTIVE/DISABLED alternating (same shape as SearchEmployees'
         * givenALargeDirectory, duplicated here rather than shared -- see
         * SearchEmployeesEnrichment for the same pattern in this file): 6 ACTIVE, 4 DISABLED.
         */
        private void givenALargeDirectory() {
            String[] lastNames = {
                "Adams", "Baker", "Cole", "Davis", "Evans", "Foster", "Grant", "Hale", "Irwin", "Jones"
            };
            EmployeeStatus[] statuses = {
                EmployeeStatus.ACTIVE,
                EmployeeStatus.DISABLED,
                EmployeeStatus.ACTIVE,
                EmployeeStatus.DISABLED,
                EmployeeStatus.ACTIVE,
                EmployeeStatus.DISABLED,
                EmployeeStatus.ACTIVE,
                EmployeeStatus.DISABLED,
                EmployeeStatus.ACTIVE,
                EmployeeStatus.ACTIVE
            };
            List<Employee> employees = new ArrayList<>();
            List<ExtPersonReplica> replicas = new ArrayList<>();
            for (int i = 0; i < lastNames.length; i++) {
                UUID personId = UUID.randomUUID();
                employees.add(employeeRow(personId, "EMP-1%03d".formatted(i), statuses[i]));
                replicas.add(replicaRow(personId, "First" + i, lastNames[i]));
            }
            when(employeeRepository.findAll()).thenReturn(employees);
            when(extPersonReplicaRepository.findByPersonIdIn(any())).thenReturn(replicas);
        }

        /**
         * The headline invariant: requesting only DISABLED rows on searchEmployees must not make
         * the ACTIVE tile disappear or shrink to zero, and the counts must always sum to the
         * q-filtered total (10 here) -- this endpoint takes no status filter at all, which is
         * what guarantees that.
         */
        @Test
        void coversTheWholeQFilteredSetRegardlessOfAnyStatusFilterOnSearchEmployees() {
            givenALargeDirectory();

            Map<String, Long> counts = service.employeeStatusCounts(null).getCounts();

            assertThat(counts.get("DISABLED")).isEqualTo(4L);
            assertThat(counts.get("ACTIVE")).isEqualTo(6L);
            assertThat(counts.values().stream().mapToLong(Long::longValue).sum())
                    .isEqualTo(10L);
        }

        @Test
        void isUnaffectedByWhichPageSearchEmployeesWouldTake() {
            givenALargeDirectory();

            // employeeStatusCounts takes no page/size at all -- calling it twice must be
            // idempotent over the same q, unlike the old per-page-call histogram it replaced.
            Map<String, Long> firstCall = service.employeeStatusCounts(null).getCounts();
            Map<String, Long> secondCall = service.employeeStatusCounts(null).getCounts();

            assertThat(firstCall).isEqualTo(secondCall);
        }

        /**
         * Finding B: {@code Employee.status} is nullable (a legacy row predating status becoming
         * a required field), and the original #2158 delivery filtered such rows out of the
         * histogram entirely, silently breaking "the counts sum to the q-filtered total" for any
         * tenant carrying one. A null-status row must land in {@link
         * EmployeeStatusCountsResponse#UNKNOWN_STATUS} instead, so the invariant holds
         * unconditionally.
         */
        @Test
        void countsSumToTheQFilteredTotal_includingANullStatusRow() {
            UUID activeId = UUID.randomUUID();
            UUID disabledId = UUID.randomUUID();
            UUID unknownStatusId = UUID.randomUUID();
            when(employeeRepository.findAll())
                    .thenReturn(List.of(
                            employeeRow(activeId, "EMP-3001", EmployeeStatus.ACTIVE),
                            employeeRow(disabledId, "EMP-3002", EmployeeStatus.DISABLED),
                            employeeRow(unknownStatusId, "EMP-3003", null)));
            when(extPersonReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(
                            replicaRow(activeId, "Ann", "One"),
                            replicaRow(disabledId, "Bea", "Two"),
                            replicaRow(unknownStatusId, "Cid", "Three")));

            Map<String, Long> counts = service.employeeStatusCounts(null).getCounts();

            assertThat(counts.get("ACTIVE")).isEqualTo(1L);
            assertThat(counts.get("DISABLED")).isEqualTo(1L);
            assertThat(counts.get(EmployeeStatusCountsResponse.UNKNOWN_STATUS)).isEqualTo(1L);
            assertThat(counts.values().stream().mapToLong(Long::longValue).sum())
                    .isEqualTo(3L);
        }

        @Test
        void aBlankOrNullQCountsEveryEmployee() {
            givenALargeDirectory();

            assertThat(service.employeeStatusCounts(null).getCounts().values().stream()
                            .mapToLong(Long::longValue)
                            .sum())
                    .isEqualTo(10L);
            assertThat(service.employeeStatusCounts("  ").getCounts().values().stream()
                            .mapToLong(Long::longValue)
                            .sum())
                    .isEqualTo(10L);
        }

        @Test
        void aQFilterNarrowsTheCountsLikeSearchEmployeesWould() {
            givenALargeDirectory();

            Map<String, Long> counts = service.employeeStatusCounts("Baker").getCounts();

            assertThat(counts.values().stream().mapToLong(Long::longValue).sum())
                    .isEqualTo(1L);
            assertThat(counts.get("DISABLED")).isEqualTo(1L);
        }
    }

    /**
     * durion#2155: the register-enrichment fields on {@link EmployeeSummaryDto} (username,
     * contactInfo, roleAssignments, primaryLocation/otherLocationCount, jobRole), gated by
     * {@code include=} and, for contactInfo, by {@code people:employee_pii:view} on top of that.
     */
    @Nested
    @DisplayName("searchEmployees register enrichment")
    class SearchEmployeesEnrichment {

        private static final UUID JANE_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f7001");
        private static final UUID LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8001");
        private static final UUID OTHER_LOCATION_ID = UUID.fromString("018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f8002");

        @AfterEach
        void clearCaller() {
            SecurityContextHolder.clearContext();
        }

        /** Authenticates the current thread as a caller holding the given authorities. */
        private void caller(String... authorities) {
            TestingAuthenticationToken authentication = new TestingAuthenticationToken("tester", null, authorities);
            authentication.setAuthenticated(true);
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        private Employee employeeRow(UUID personId, String employeeNumber, UUID jobRoleId) {
            return Employee.builder()
                    .id(UUID.randomUUID())
                    .personId(personId)
                    .employeeNumber(employeeNumber)
                    .status(EmployeeStatus.ACTIVE)
                    .jobRoleId(jobRoleId)
                    .build();
        }

        private ExtPersonReplica replicaRow(UUID personId, String firstName, String lastName) {
            ExtPersonReplica person = new ExtPersonReplica();
            person.setPersonId(personId);
            person.setFirstName(firstName);
            person.setLastName(lastName);
            person.setPrimaryEmail(firstName.toLowerCase(java.util.Locale.ROOT) + "@example.com");
            person.setPrimaryPhone("555-0100");
            return person;
        }

        private EmployeeLocationAssignment assignment(UUID personId, UUID locationId, boolean primary) {
            return EmployeeLocationAssignment.builder()
                    .id(UUID.randomUUID())
                    .employee(Employee.builder().personId(personId).build())
                    .locationId(locationId)
                    .role("TECHNICIAN")
                    .isPrimary(primary)
                    .effectiveFrom(LocalDate.of(2026, 1, 1))
                    .status(AssignmentStatus.ACTIVE)
                    .build();
        }

        private static final List<EmployeeSearchInclude> EVERY_INCLUDE = List.of(
                EmployeeSearchInclude.USERNAME,
                EmployeeSearchInclude.CONTACT_INFO,
                EmployeeSearchInclude.ROLE_ASSIGNMENTS,
                EmployeeSearchInclude.LOCATION,
                EmployeeSearchInclude.JOB_ROLE);

        @Test
        @DisplayName("a full row renders every column in one service call")
        void aFullRowRendersEveryColumnInOneServiceCall() {
            caller(PeoplePermissions.EMPLOYEE_VIEW, PeoplePermissions.EMPLOYEE_PII_VIEW);

            when(employeeRepository.findAll()).thenReturn(List.of(employeeRow(JANE_ID, "EMP-1000", JOB_ROLE_ID)));
            when(extPersonReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(replicaRow(JANE_ID, "Jane", "Smith")));
            when(personUsernameService.usernamesByPersonId(any())).thenReturn(Map.of(JANE_ID, "jane.smith"));
            when(roleAssignmentReplicaService.findActiveRoleAssignmentsByUsernames(any()))
                    .thenReturn(Map.of(
                            "jane.smith",
                            List.of(EmployeeRoleAssignmentDto.builder()
                                    .assignmentId(UUID.randomUUID())
                                    .roleId(UUID.randomUUID())
                                    .roleName("SHOP_MANAGER")
                                    .roleLocationScope("ALL")
                                    .effectiveStartDate(LocalDateTime.of(2026, 1, 1, 0, 0))
                                    .build())));
            when(employeeLocationAssignmentRepository.findActiveByPersonIdIn(any(), any()))
                    .thenReturn(List.of(assignment(JANE_ID, LOCATION_ID, true)));
            when(locationReferenceService.findLocationNames(any())).thenReturn(Map.of(LOCATION_ID, "Charlotte Main"));
            when(jobRoleRepository.findAllById(any())).thenReturn(List.of(jobRole()));

            EmployeeSummaryDto row = service.searchEmployees(null, null, null, 0, 20, EVERY_INCLUDE)
                    .items()
                    .get(0);

            assertThat(row.getUsername()).isEqualTo("jane.smith");
            assertThat(row.getContactInfo()).isNotNull();
            assertThat(row.getContactInfo().getPrimaryEmail()).isEqualTo("jane@example.com");
            assertThat(row.getContactInfo().getPrimaryPhone()).isEqualTo("555-0100");
            assertThat(row.getRoleAssignments())
                    .extracting(EmployeeRoleAssignmentDto::getRoleName)
                    .containsExactly("SHOP_MANAGER");
            assertThat(row.getPrimaryLocation()).isNotNull();
            assertThat(row.getPrimaryLocation().getId()).isEqualTo(LOCATION_ID);
            assertThat(row.getPrimaryLocation().getName()).isEqualTo("Charlotte Main");
            assertThat(row.getOtherLocationCount()).isZero();
            assertThat(row.getJobRole()).isNotNull();
            assertThat(row.getJobRole().getId()).isEqualTo(JOB_ROLE_ID);
            assertThat(row.getJobRole().getName()).isEqualTo("Lead Technician");
        }

        /**
         * Pins the #2155 headline constraint: a batched enrichment lookup must be invoked with
         * exactly the page window's usernames, never the whole q-/status-filtered result set's.
         * Four employees match the search (page size 2), so a naive implementation that batches
         * before paging would send all four usernames through; this asserts on the exact set
         * received by the mocked collaborators instead of merely counting invocations, so it
         * fails on that bug even though the total employee count (4) is small enough that a
         * count-only assertion could accidentally still pass.
         */
        @Test
        @DisplayName("the batch role lookup runs against exactly the window's usernames, not the whole result set")
        void enrichmentBatchLookupTouchesOnlyTheWindow() {
            caller(PeoplePermissions.EMPLOYEE_VIEW);

            UUID adamsId = UUID.randomUUID();
            UUID bakerId = UUID.randomUUID();
            UUID coleId = UUID.randomUUID();
            UUID davisId = UUID.randomUUID();
            when(employeeRepository.findAll())
                    .thenReturn(List.of(
                            employeeRow(adamsId, "EMP-1", null),
                            employeeRow(bakerId, "EMP-2", null),
                            employeeRow(coleId, "EMP-3", null),
                            employeeRow(davisId, "EMP-4", null)));
            when(extPersonReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(
                            replicaRow(adamsId, "A", "Adams"),
                            replicaRow(bakerId, "B", "Baker"),
                            replicaRow(coleId, "C", "Cole"),
                            replicaRow(davisId, "D", "Davis")));

            Map<UUID, String> usernamesByPersonId = new LinkedHashMap<>();
            usernamesByPersonId.put(adamsId, "a.adams");
            usernamesByPersonId.put(bakerId, "b.baker");
            usernamesByPersonId.put(coleId, "c.cole");
            usernamesByPersonId.put(davisId, "d.davis");
            when(personUsernameService.usernamesByPersonId(any())).thenAnswer(invocation -> {
                Collection<UUID> requested = invocation.getArgument(0);
                Map<UUID, String> result = new LinkedHashMap<>();
                requested.forEach(id -> result.put(id, usernamesByPersonId.get(id)));
                return result;
            });
            when(roleAssignmentReplicaService.findActiveRoleAssignmentsByUsernames(any()))
                    .thenReturn(Map.of());

            // lastName order Adams, Baker, Cole, Davis; page size 2, page 0 -> window is [Adams, Baker].
            service.searchEmployees(null, null, null, 0, 2, List.of(EmployeeSearchInclude.ROLE_ASSIGNMENTS));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<UUID>> personIdsCaptor = ArgumentCaptor.forClass(Collection.class);
            verify(personUsernameService).usernamesByPersonId(personIdsCaptor.capture());
            assertThat(personIdsCaptor.getValue()).containsExactlyInAnyOrder(adamsId, bakerId);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<String>> usernamesCaptor = ArgumentCaptor.forClass(Collection.class);
            verify(roleAssignmentReplicaService).findActiveRoleAssignmentsByUsernames(usernamesCaptor.capture());
            assertThat(usernamesCaptor.getValue()).containsExactlyInAnyOrder("a.adams", "b.baker");
        }

        @Test
        @DisplayName("a caller without people:employee_pii:view gets 200 with email/phone absent")
        void contactInfoAbsentWithoutThePiiPermission() {
            caller(PeoplePermissions.EMPLOYEE_VIEW);

            when(employeeRepository.findAll()).thenReturn(List.of(employeeRow(JANE_ID, "EMP-1000", null)));
            when(extPersonReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(replicaRow(JANE_ID, "Jane", "Smith")));

            EmployeeSummaryDto row = service.searchEmployees(
                            null, null, null, 0, 20, List.of(EmployeeSearchInclude.CONTACT_INFO))
                    .items()
                    .get(0);

            assertThat(row.getContactInfo()).isNull();
        }

        @Test
        @DisplayName("include= absent leaves the response identical to the pre-#2155 thin shape")
        void includeAbsentLeavesTheThinShapeUnchanged() {
            caller(PeoplePermissions.EMPLOYEE_VIEW, PeoplePermissions.EMPLOYEE_PII_VIEW);

            when(employeeRepository.findAll()).thenReturn(List.of(employeeRow(JANE_ID, "EMP-1000", JOB_ROLE_ID)));
            when(extPersonReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(replicaRow(JANE_ID, "Jane", "Smith")));

            EmployeeSummaryDto row = service.searchEmployees(null, null, null, 0, 20, null)
                    .items()
                    .get(0);

            assertThat(row.getUsername()).isNull();
            assertThat(row.getContactInfo()).isNull();
            assertThat(row.getRoleAssignments()).isNull();
            assertThat(row.getPrimaryLocation()).isNull();
            assertThat(row.getOtherLocationCount()).isNull();
            assertThat(row.getJobRole()).isNull();
            verifyNoInteractions(
                    personUsernameService,
                    roleAssignmentReplicaService,
                    employeeLocationAssignmentRepository,
                    locationReferenceService,
                    jobRoleRepository);
        }

        /** Also true of an empty {@code include=} list, e.g. a client that sends the param with no values. */
        @Test
        @DisplayName("an empty include= list also leaves the response identical to the thin shape")
        void anEmptyIncludeListAlsoLeavesTheThinShapeUnchanged() {
            caller(PeoplePermissions.EMPLOYEE_VIEW, PeoplePermissions.EMPLOYEE_PII_VIEW);

            when(employeeRepository.findAll()).thenReturn(List.of(employeeRow(JANE_ID, "EMP-1000", null)));
            when(extPersonReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(replicaRow(JANE_ID, "Jane", "Smith")));

            EmployeeSummaryDto row = service.searchEmployees(null, null, null, 0, 20, List.of())
                    .items()
                    .get(0);

            assertThat(row.getUsername()).isNull();
            assertThat(row.getRoleAssignments()).isNull();
        }

        @Test
        @DisplayName("an employee with no user link, no roles, no job role and no location tolerates partial data")
        void tolerantOfMissingReplicaData() {
            caller(PeoplePermissions.EMPLOYEE_VIEW, PeoplePermissions.EMPLOYEE_PII_VIEW);

            // No replica row at all: exercises the contactInfo-null-from-missing-replica path too.
            when(employeeRepository.findAll()).thenReturn(List.of(employeeRow(JANE_ID, "EMP-1000", null)));
            when(extPersonReplicaRepository.findByPersonIdIn(any())).thenReturn(List.of());
            when(personUsernameService.usernamesByPersonId(any())).thenReturn(Map.of());
            when(roleAssignmentReplicaService.findActiveRoleAssignmentsByUsernames(any()))
                    .thenReturn(Map.of());
            when(employeeLocationAssignmentRepository.findActiveByPersonIdIn(any(), any()))
                    .thenReturn(List.of());

            EmployeeSummaryDto row = service.searchEmployees(null, null, null, 0, 20, EVERY_INCLUDE)
                    .items()
                    .get(0);

            assertThat(row.getUsername()).isNull();
            assertThat(row.getContactInfo()).isNull();
            assertThat(row.getRoleAssignments()).isEmpty();
            assertThat(row.getPrimaryLocation()).isNull();
            assertThat(row.getOtherLocationCount()).isZero();
            assertThat(row.getJobRole()).isNull();
            verifyNoInteractions(jobRoleRepository);
        }

        @Test
        @DisplayName("multiple active locations produce the correct otherLocationCount")
        void multipleLocationsProduceTheCorrectOtherLocationCount() {
            caller(PeoplePermissions.EMPLOYEE_VIEW);

            when(employeeRepository.findAll()).thenReturn(List.of(employeeRow(JANE_ID, "EMP-1000", null)));
            when(extPersonReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(replicaRow(JANE_ID, "Jane", "Smith")));
            when(employeeLocationAssignmentRepository.findActiveByPersonIdIn(any(), any()))
                    .thenReturn(List.of(
                            assignment(JANE_ID, LOCATION_ID, true),
                            assignment(JANE_ID, OTHER_LOCATION_ID, false),
                            assignment(JANE_ID, UUID.randomUUID(), false)));
            when(locationReferenceService.findLocationNames(any())).thenReturn(Map.of(LOCATION_ID, "Charlotte Main"));

            EmployeeSummaryDto row = service.searchEmployees(
                            null, null, null, 0, 20, List.of(EmployeeSearchInclude.LOCATION))
                    .items()
                    .get(0);

            assertThat(row.getPrimaryLocation()).isNotNull();
            assertThat(row.getPrimaryLocation().getId()).isEqualTo(LOCATION_ID);
            assertThat(row.getPrimaryLocation().getName()).isEqualTo("Charlotte Main");
            assertThat(row.getOtherLocationCount()).isEqualTo(2);
        }

        /**
         * Defensive branch: DECISION-PEOPLE-004 expects at most one active primary per person, but
         * a lagging replica or a person mid-reassignment could momentarily show none flagged. The
         * row must still report the active count rather than guessing which assignment is primary.
         */
        @Test
        @DisplayName("no assignment flagged primary yields no primaryLocation but still counts every active assignment")
        void noFlaggedPrimaryYieldsNullPrimaryWithFullCount() {
            caller(PeoplePermissions.EMPLOYEE_VIEW);

            when(employeeRepository.findAll()).thenReturn(List.of(employeeRow(JANE_ID, "EMP-1000", null)));
            when(extPersonReplicaRepository.findByPersonIdIn(any()))
                    .thenReturn(List.of(replicaRow(JANE_ID, "Jane", "Smith")));
            when(employeeLocationAssignmentRepository.findActiveByPersonIdIn(any(), any()))
                    .thenReturn(List.of(
                            assignment(JANE_ID, LOCATION_ID, false), assignment(JANE_ID, OTHER_LOCATION_ID, false)));

            EmployeeSummaryDto row = service.searchEmployees(
                            null, null, null, 0, 20, List.of(EmployeeSearchInclude.LOCATION))
                    .items()
                    .get(0);

            assertThat(row.getPrimaryLocation()).isNull();
            assertThat(row.getOtherLocationCount()).isEqualTo(2);
        }
    }
}
