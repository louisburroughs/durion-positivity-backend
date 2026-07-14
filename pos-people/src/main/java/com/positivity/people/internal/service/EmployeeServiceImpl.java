package com.positivity.people.internal.service;

import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.DisableEmployeeRequestDto;
import com.positivity.people.internal.dto.EmployeeContactInfoDto;
import com.positivity.people.internal.dto.EmployeeIdentityDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
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
import com.positivity.people.service.EmployeeService;
import com.positivity.security.common.SecurityContextHelper;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Employment lifecycle (ADR-0044 §6 Phase 3.2, #875). Identity attributes (names, contacts) are
 * owned by pos-people-contact: writes travel as {@code people-contact.person.upsert-requested}
 * commands (the sender generates the personId for creates), reads come from the
 * {@code ext_people_contact_person} replica. Create/update responses echo the request's identity
 * fields so callers see their write immediately while the fact event catches the replica up.
 * Every employment mutation also publishes a {@code people.employee.updated} fact.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeServiceImpl implements EmployeeService {

    private final Clock clock;

    private static final String SYSTEM_ACTOR = "system";

    private final ExtPersonReplicaRepository extPersonReplicaRepository;

    private final EmployeeRepository employeeRepository;

    private final EmployeeOffboardingRetryRepository offboardingRetryRepository;

    private final PeopleEventPublisher peopleEventPublisher;

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<EmployeeIdentityDto> resolveByEmployeeNumber(@NonNull String employeeNumber) {
        return employeeRepository
                .findByEmployeeNumberIgnoreCase(employeeNumber)
                .map(employee -> EmployeeIdentityDto.builder()
                        .employeeId(employee.getId())
                        .personId(employee.getPersonId())
                        .employeeNumber(employee.getEmployeeNumber())
                        .status(
                                employee.getStatus() != null
                                        ? employee.getStatus().name()
                                        : null)
                        .active(employee.getStatus() == EmployeeStatus.ACTIVE)
                        .build());
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto createEmployee(@NonNull CreateEmployeeRequest request) {
        validateEmployeeRequest(request.getHireDate(), request.getTerminationDate());
        List<String> warnings = evaluateDuplicatePolicy(
                null,
                request.getDuplicatePolicy(),
                request.getEmployeeNumber(),
                request.getContactInfo(),
                request.getFirstName(),
                request.getLastName());

        // Identity is owned by pos-people-contact: generate the person id here so the employee
        // row can reference it immediately, and send the attributes as an upsert command.
        UUID personId = UUIDv7Generator.generate();
        requestIdentityUpsert(
                personId,
                request.getFirstName(),
                request.getLastName(),
                request.getPreferredName(),
                request.getContactInfo());

        Employee employee = Employee.builder().personId(personId).build();
        applyEmployment(
                employee,
                request.getEmployeeNumber(),
                request.getStatus(),
                request.getHireDate(),
                request.getTerminationDate());
        Employee savedEmployee = employeeRepository.save(employee);
        peopleEventPublisher.publishEmployeeUpdated(savedEmployee);

        return profileFromRequestIdentity(
                personId,
                request.getFirstName(),
                request.getLastName(),
                request.getPreferredName(),
                request.getContactInfo(),
                savedEmployee,
                warnings);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull EmployeeProfileDto getEmployee(@NonNull UUID employeeId) {
        // Pre-split contract: the path parameter is the person id (employee id == person id at
        // the API surface). The replica row may lag a just-issued upsert command, so an existing
        // employee row alone is enough to serve the profile.
        ExtPersonReplica person =
                extPersonReplicaRepository.findById(employeeId).orElse(null);
        Employee employee = employeeRepository.findByPersonId(employeeId).orElse(null);
        if (person == null && employee == null) {
            throw new PersonNotFoundException(employeeId);
        }
        return profileFromReplica(employeeId, person, employee, List.of());
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto updateEmployee(
            @NonNull UUID employeeId, @NonNull UpdateEmployeeRequest request) {
        validateEmployeeRequest(request.getHireDate(), request.getTerminationDate());

        Employee employee = employeeRepository.findByPersonId(employeeId).orElse(null);
        if (employee == null && !extPersonReplicaRepository.existsById(employeeId)) {
            throw new PersonNotFoundException(employeeId);
        }

        List<String> warnings = evaluateDuplicatePolicy(
                employeeId,
                request.getDuplicatePolicy(),
                request.getEmployeeNumber(),
                request.getContactInfo(),
                request.getFirstName(),
                request.getLastName());

        requestIdentityUpsert(
                employeeId,
                request.getFirstName(),
                request.getLastName(),
                request.getPreferredName(),
                request.getContactInfo());

        if (employee == null) {
            employee = Employee.builder().personId(employeeId).build();
        }
        EmployeeStatus previousStatus = employee.getStatus();
        applyEmployment(
                employee,
                request.getEmployeeNumber(),
                request.getStatus(),
                request.getHireDate(),
                request.getTerminationDate());
        if (previousStatus != request.getStatus()) {
            employee.setStatusEffectiveAt(Instant.now(clock));
        }
        Employee savedEmployee = employeeRepository.save(employee);
        peopleEventPublisher.publishEmployeeUpdated(savedEmployee);

        return profileFromRequestIdentity(
                employeeId,
                request.getFirstName(),
                request.getLastName(),
                request.getPreferredName(),
                request.getContactInfo(),
                savedEmployee,
                warnings);
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto disableEmployee(
            @NonNull UUID employeeId, @NonNull DisableEmployeeRequestDto request) {
        Employee employee = employeeRepository
                .findByPersonId(employeeId)
                .orElseThrow(() -> new PersonNotFoundException(employeeId));

        EmployeeStatus currentStatus = employee.getStatus();
        if (currentStatus == EmployeeStatus.DISABLED || currentStatus == EmployeeStatus.TERMINATED) {
            throw new IllegalArgumentException("Employee is already DISABLED or TERMINATED");
        }
        if (currentStatus != EmployeeStatus.ACTIVE) {
            throw new IllegalArgumentException("Only ACTIVE employees can be disabled");
        }

        employee.setStatus(EmployeeStatus.DISABLED);
        employee.setStatusEffectiveAt(Instant.now(clock));
        Employee savedEmployee = employeeRepository.save(employee);
        peopleEventPublisher.publishEmployeeUpdated(savedEmployee);

        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);
        try {
            applyAssignmentPolicy(employeeId, request, actorId);
        } catch (Exception exception) {
            log.warn(
                    "Offboarding downstream action failed for employee {}. Queuing retry. Reason: {}",
                    employeeId,
                    exception.getMessage());
            queueOffboardingRetry(employeeId, request, actorId, exception.getMessage());
        }

        ExtPersonReplica person =
                extPersonReplicaRepository.findById(employeeId).orElse(null);
        return profileFromReplica(employeeId, person, savedEmployee, List.of());
    }

    /** Queue the identity attributes as an upsert command toward pos-people-contact. */
    private void requestIdentityUpsert(
            UUID personId, String firstName, String lastName, String preferredName, EmployeeContactInfoDto contact) {
        peopleEventPublisher.requestPersonUpsert(new PersonUpsertRequestedV1(
                personId,
                normalize(firstName),
                normalize(lastName),
                normalize(preferredName),
                contact == null ? null : normalize(contact.getPrimaryEmail()),
                contact == null ? null : normalize(contact.getSecondaryEmail()),
                extractPhones(contact)));
    }

    private void validateEmployeeRequest(java.time.LocalDate hireDate, java.time.LocalDate terminationDate) {
        if (terminationDate != null && terminationDate.isBefore(hireDate)) {
            throw new SemanticValidationException("terminationDate must be greater than or equal to hireDate");
        }
    }

    private List<String> evaluateDuplicatePolicy(
            UUID employeeId,
            DuplicatePolicy duplicatePolicy,
            String employeeNumber,
            EmployeeContactInfoDto contactInfo,
            String firstName,
            String lastName) {
        DuplicatePolicy policy = duplicatePolicy != null ? duplicatePolicy : DuplicatePolicy.STRICT;

        DuplicateSignals duplicateSignals = collectDuplicateSignals(employeeId, employeeNumber, contactInfo);
        List<String> warnings = new ArrayList<>();
        if (policy == DuplicatePolicy.STRICT && duplicateSignals.hasAny()) {
            throw new IllegalStateException("Duplicate employee detected by STRICT policy");
        }

        if (policy != DuplicatePolicy.BALANCED) {
            return warnings;
        }

        if (duplicateSignals.hasAny()) {
            warnings.add("Potential duplicate detected; request accepted due to BALANCED duplicatePolicy");
        }

        if (hasAmbiguousNameMatch(employeeId, firstName, lastName)) {
            warnings.add("Ambiguous duplicate match detected by name similarity");
        }

        return warnings;
    }

    /**
     * Duplicate signals over the identity replica (email/phone) and local employment rows
     * (employee number). Replica-based checks are eventually consistent — a person written
     * moments ago may not be visible yet, which is acceptable for a warning/policy signal.
     */
    private DuplicateSignals collectDuplicateSignals(
            UUID employeeId, String employeeNumber, EmployeeContactInfoDto contactInfo) {
        String primaryEmail = contactInfo != null ? normalize(contactInfo.getPrimaryEmail()) : null;
        String primaryPhone = contactInfo != null ? normalize(contactInfo.getPrimaryPhone()) : null;
        String secondaryPhone = contactInfo != null ? normalize(contactInfo.getSecondaryPhone()) : null;

        boolean duplicateEmployeeNumber = employeeNumber != null
                && (employeeId == null
                        ? employeeRepository.existsByEmployeeNumberIgnoreCase(employeeNumber)
                        : employeeRepository.existsByEmployeeNumberIgnoreCaseAndPersonIdNot(
                                employeeNumber, employeeId));

        boolean duplicatePrimaryEmail = primaryEmail != null
                && extPersonReplicaRepository.findByPrimaryEmailIgnoreCase(primaryEmail).stream()
                        .anyMatch(person ->
                                employeeId == null || !person.getPersonId().equals(employeeId));

        boolean duplicatePhone = hasDuplicatePhone(employeeId, primaryPhone, secondaryPhone);
        return new DuplicateSignals(duplicateEmployeeNumber, duplicatePrimaryEmail, duplicatePhone);
    }

    private boolean hasAmbiguousNameMatch(UUID employeeId, String firstName, String lastName) {
        if (firstName == null || firstName.isBlank() || lastName == null || lastName.isBlank()) {
            return false;
        }
        return extPersonReplicaRepository.findByLastNameIgnoreCase(lastName).stream()
                .filter(person -> firstName.equalsIgnoreCase(person.getFirstName()))
                .anyMatch(person -> employeeId == null || !person.getPersonId().equals(employeeId));
    }

    /** Employment attributes on the Employee record. */
    private void applyEmployment(
            Employee employee,
            String employeeNumber,
            EmployeeStatus status,
            java.time.LocalDate hireDate,
            java.time.LocalDate terminationDate) {
        employee.setEmployeeNumber(employeeNumber);
        employee.setStatus(status);
        employee.setHireDate(hireDate);
        employee.setTerminationDate(terminationDate);
        if (employee.getStatusEffectiveAt() == null) {
            employee.setStatusEffectiveAt(Instant.now(clock));
        }
    }

    /** Primary + secondary phone from contact info, normalized and blank-filtered. */
    private List<String> extractPhones(EmployeeContactInfoDto contactInfo) {
        if (contactInfo == null) {
            return List.of();
        }
        return Stream.of(normalize(contactInfo.getPrimaryPhone()), normalize(contactInfo.getSecondaryPhone()))
                .filter(value -> value != null && !value.isBlank())
                .toList();
    }

    private boolean hasDuplicatePhone(UUID employeeId, String primaryPhone, String secondaryPhone) {
        if (primaryPhone == null && secondaryPhone == null) {
            return false;
        }

        return hasDuplicatePhoneValue(employeeId, primaryPhone) || hasDuplicatePhoneValue(employeeId, secondaryPhone);
    }

    private boolean hasDuplicatePhoneValue(UUID employeeId, String phoneValue) {
        if (phoneValue == null || phoneValue.isBlank()) {
            return false;
        }
        return extPersonReplicaRepository.findByPrimaryPhoneOrSecondaryPhone(phoneValue, phoneValue).stream()
                .anyMatch(person -> employeeId == null || !person.getPersonId().equals(employeeId));
    }

    /** Profile for create/update responses: identity echoed from the request (replica may lag). */
    private EmployeeProfileDto profileFromRequestIdentity(
            UUID personId,
            String firstName,
            String lastName,
            String preferredName,
            EmployeeContactInfoDto contactInfo,
            Employee employee,
            List<String> warnings) {
        return EmployeeProfileDto.builder()
                .id(personId)
                .firstName(normalize(firstName))
                .lastName(normalize(lastName))
                .preferredName(normalize(preferredName))
                .employeeNumber(employee != null ? employee.getEmployeeNumber() : null)
                .status(employee != null ? employee.getStatus() : null)
                .hireDate(employee != null ? employee.getHireDate() : null)
                .terminationDate(employee != null ? employee.getTerminationDate() : null)
                .contactInfo(contactInfo)
                .statusEffectiveAt(employee != null ? employee.getStatusEffectiveAt() : null)
                .createdAt(employee != null ? employee.getCreatedAt() : null)
                .updatedAt(employee != null ? employee.getUpdatedAt() : null)
                .warnings(warnings)
                .build();
    }

    /** Profile for reads: identity from the {@code ext_people_contact_person} replica. */
    private EmployeeProfileDto profileFromReplica(
            UUID personId, @Nullable ExtPersonReplica person, @Nullable Employee employee, List<String> warnings) {
        return EmployeeProfileDto.builder()
                .id(personId)
                .firstName(person != null ? person.getFirstName() : null)
                .lastName(person != null ? person.getLastName() : null)
                .preferredName(person != null ? person.getPreferredName() : null)
                .employeeNumber(employee != null ? employee.getEmployeeNumber() : null)
                .status(employee != null ? employee.getStatus() : null)
                .hireDate(employee != null ? employee.getHireDate() : null)
                .terminationDate(employee != null ? employee.getTerminationDate() : null)
                .contactInfo(buildContactInfo(person))
                .statusEffectiveAt(employee != null ? employee.getStatusEffectiveAt() : null)
                .createdAt(
                        person != null
                                ? person.getPersonCreatedAt()
                                : (employee != null ? employee.getCreatedAt() : null))
                .updatedAt(
                        person != null
                                ? person.getPersonUpdatedAt()
                                : (employee != null ? employee.getUpdatedAt() : null))
                .warnings(warnings)
                .build();
    }

    /** Contact info from the replica's flattened contact columns. */
    private @Nullable EmployeeContactInfoDto buildContactInfo(@Nullable ExtPersonReplica person) {
        if (person == null) {
            return null;
        }
        String primaryEmail = normalize(person.getPrimaryEmail());
        String secondaryEmail = normalize(person.getSecondaryEmail());
        String primaryPhone = normalize(person.getPrimaryPhone());
        String secondaryPhone = normalize(person.getSecondaryPhone());

        if (primaryEmail == null && secondaryEmail == null && primaryPhone == null && secondaryPhone == null) {
            return null;
        }

        EmployeeContactInfoDto dto = new EmployeeContactInfoDto();
        dto.setPrimaryEmail(primaryEmail);
        dto.setSecondaryEmail(secondaryEmail);
        dto.setPrimaryPhone(primaryPhone);
        dto.setSecondaryPhone(secondaryPhone);
        return dto;
    }

    private void applyAssignmentPolicy(UUID employeeId, DisableEmployeeRequestDto request, String actorId) {
        AssignmentTerminationPolicy policy = request.getAssignmentPolicy() != null
                ? request.getAssignmentPolicy()
                : AssignmentTerminationPolicy.IMMEDIATE;

        switch (policy) {
            case IMMEDIATE ->
                log.info("Applying IMMEDIATE assignment offboarding for employee {} by actor {}", employeeId, actorId);
            case GRACE_PERIOD ->
                log.info(
                        "Applying GRACE_PERIOD assignment offboarding for employee {} with assignmentEndDate {} by actor {}",
                        employeeId,
                        request.getAssignmentEndDate(),
                        actorId);
            default -> throw new IllegalStateException("Unsupported assignment policy");
        }
    }

    private void queueOffboardingRetry(
            UUID employeeId, DisableEmployeeRequestDto request, String actorId, String failureReason) {
        EmployeeOffboardingRetry retry = new EmployeeOffboardingRetry();
        retry.setEmployeeId(employeeId);
        retry.setAssignmentPolicy(
                request.getAssignmentPolicy() != null
                        ? request.getAssignmentPolicy()
                        : AssignmentTerminationPolicy.IMMEDIATE);
        retry.setDisableReason(request.getDisableReason());
        retry.setActorId(actorId);
        retry.setFailureReason(failureReason != null ? failureReason : "unknown");
        retry.setAttempts(0);
        retry.setNextAttemptAt(Instant.now(clock).plusSeconds(300));
        offboardingRetryRepository.save(retry);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private record DuplicateSignals(
            boolean duplicateEmployeeNumber, boolean duplicatePrimaryEmail, boolean duplicatePhone) {
        private boolean hasAny() {
            return duplicateEmployeeNumber || duplicatePrimaryEmail || duplicatePhone;
        }
    }
}
