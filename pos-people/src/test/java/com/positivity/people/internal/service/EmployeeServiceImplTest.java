package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import com.positivity.people.internal.dto.EmployeeSummaryDto;
import com.positivity.people.internal.dto.PagedResponse;
import com.positivity.people.internal.dto.UpdateEmployeeRequest;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.DuplicatePolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.SemanticValidationException;
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

    private EmployeeServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new EmployeeServiceImpl(
                CLOCK,
                extPersonReplicaRepository,
                employeeRepository,
                offboardingRetryRepository,
                peopleEventPublisher);
        when(employeeRepository.save(any(Employee.class))).thenAnswer(invocation -> invocation.getArgument(0));
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
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already DISABLED or TERMINATED");
        }

        @Test
        void rejectsATerminatedEmployee() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employee(EmployeeStatus.TERMINATED)));
            DisableEmployeeRequestDto request = disableRequest(AssignmentTerminationPolicy.IMMEDIATE);

            assertThatThrownBy(() -> service.disableEmployee(PERSON_ID, request))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void rejectsAnEmployeeThatIsNeitherActiveNorAlreadyDisabled() {
            when(employeeRepository.findByPersonId(PERSON_ID))
                    .thenReturn(Optional.of(employee(EmployeeStatus.SUSPENDED)));
            DisableEmployeeRequestDto request = disableRequest(AssignmentTerminationPolicy.IMMEDIATE);

            assertThatThrownBy(() -> service.disableEmployee(PERSON_ID, request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Only ACTIVE employees can be disabled");
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

        @Test
        void aBlankQueryListsEveryEmployee() {
            givenTheDirectory();

            PagedResponse<EmployeeSummaryDto> page = service.searchEmployees("  ", 0, 20);

            assertThat(page.items()).hasSize(3);
            assertThat(page.totalElements()).isEqualTo(3);
            assertThat(page.totalPages()).isEqualTo(1);
        }

        @Test
        void aNullQueryListsEveryEmployee() {
            givenTheDirectory();

            assertThat(service.searchEmployees(null, 0, 20).items()).hasSize(3);
        }

        @Test
        void matchesByLastName() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees("smith", 0, 20).items();

            assertThat(results)
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0001");
        }

        @Test
        void matchesByPreferredName() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees("Janie", 0, 20).items();

            assertThat(results)
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0001");
        }

        @Test
        void matchesByEmployeeNumber() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees("0002", 0, 20).items();

            assertThat(results)
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0002");
        }

        @Test
        void matchingIsCaseInsensitive() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees("DOE", 0, 20).items();

            assertThat(results)
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0002");
        }

        @Test
        void anEmployeeWithNoReplicaRowIsStillFoundByNumberAndCarriesNullNames() {
            givenTheDirectory();

            List<EmployeeSummaryDto> results =
                    service.searchEmployees("EMP-0003", 0, 20).items();

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
                    service.searchEmployees(null, 0, 20).items();

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
            PagedResponse<EmployeeSummaryDto> firstPage = service.searchEmployees(null, 0, 2);
            assertThat(firstPage.items())
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0002", "EMP-0001");
            assertThat(firstPage.totalElements()).isEqualTo(3);
            assertThat(firstPage.totalPages()).isEqualTo(2);

            PagedResponse<EmployeeSummaryDto> secondPage = service.searchEmployees(null, 1, 2);
            assertThat(secondPage.items())
                    .extracting(EmployeeSummaryDto::getEmployeeNumber)
                    .containsExactly("EMP-0003");
            assertThat(secondPage.totalElements()).isEqualTo(3);
            assertThat(secondPage.totalPages()).isEqualTo(2);
        }

        @Test
        void anOutOfRangePageReturnsEmptyItemsWithCorrectTotals() {
            givenTheDirectory();

            PagedResponse<EmployeeSummaryDto> page = service.searchEmployees(null, 5, 20);

            assertThat(page.items()).isEmpty();
            assertThat(page.totalElements()).isEqualTo(3);
            assertThat(page.totalPages()).isEqualTo(1);
            assertThat(page.page()).isEqualTo(5);
            assertThat(page.size()).isEqualTo(20);
        }
    }
}
