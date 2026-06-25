package com.positivity.people.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.DisableEmployeeRequestDto;
import com.positivity.people.internal.dto.EmployeeContactInfoDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.UpdateEmployeeRequest;
import com.positivity.people.internal.entity.Employee;
import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.DuplicatePolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.SemanticValidationException;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.PersonRepository;
import com.positivity.people.service.EmployeeService;
import com.positivity.security.common.SecurityContextHelper;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmployeeServiceImpl implements EmployeeService {

    private final Clock clock;

    private static final String SYSTEM_ACTOR = "system";

    private final PersonRepository personRepository;

    private final EmployeeRepository employeeRepository;

    private final PersonWorkPhoneService workPhoneService;

    private final PersonEmailService emailService;

    private final EmployeeOffboardingRetryRepository offboardingRetryRepository;

    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto createEmployee(@NonNull CreateEmployeeRequest request) {
        validateEmployeeRequest(request.getHireDate(), request.getTerminationDate());
        List<String> warnings = evaluateDuplicatePolicy(
                null,
                request.getDuplicatePolicy(),
                request.getEmployeeNumber(),
                request.getContactInfo(),
                request.getLegalName());

        Person person = new Person();
        applyIdentity(person, request.getLegalName(), request.getPreferredName(), request.getContactInfo());
        Person savedPerson = personRepository.save(person);

        Employee employee = Employee.builder().personId(savedPerson.getId()).build();
        applyEmployment(
                employee,
                request.getEmployeeNumber(),
                request.getStatus(),
                request.getHireDate(),
                request.getTerminationDate());
        Employee savedEmployee = employeeRepository.save(employee);

        replaceContactPoints(savedPerson.getId(), request.getContactInfo());
        return toEmployeeProfile(savedPerson, savedEmployee, warnings);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull EmployeeProfileDto getEmployee(@NonNull UUID employeeId) {
        Person person =
                personRepository.findById(employeeId).orElseThrow(() -> new PersonNotFoundException(employeeId));
        Employee employee = employeeRepository.findByPersonId(employeeId).orElse(null);
        return toEmployeeProfile(person, employee, List.of());
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto updateEmployee(
            @NonNull UUID employeeId, @NonNull UpdateEmployeeRequest request) {
        validateEmployeeRequest(request.getHireDate(), request.getTerminationDate());

        Person person =
                personRepository.findById(employeeId).orElseThrow(() -> new PersonNotFoundException(employeeId));

        List<String> warnings = evaluateDuplicatePolicy(
                employeeId,
                request.getDuplicatePolicy(),
                request.getEmployeeNumber(),
                request.getContactInfo(),
                request.getLegalName());

        applyIdentity(person, request.getLegalName(), request.getPreferredName(), request.getContactInfo());
        Person savedPerson = personRepository.save(person);

        Employee employee = employeeRepository
                .findByPersonId(employeeId)
                .orElseGet(() -> Employee.builder().personId(employeeId).build());
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

        replaceContactPoints(savedPerson.getId(), request.getContactInfo());
        return toEmployeeProfile(savedPerson, savedEmployee, warnings);
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto disableEmployee(
            @NonNull UUID employeeId, @NonNull DisableEmployeeRequestDto request) {
        Person person =
                personRepository.findById(employeeId).orElseThrow(() -> new PersonNotFoundException(employeeId));
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

        return toEmployeeProfile(person, savedEmployee, List.of());
    }

    /** Persist email + phone contact points from the request's contact info (replaces existing). */
    private void replaceContactPoints(UUID personId, EmployeeContactInfoDto contactInfo) {
        workPhoneService.replaceWorkPhones(personId, extractPhones(contactInfo));
        emailService.replaceEmails(
                personId,
                contactInfo == null ? null : normalize(contactInfo.getPrimaryEmail()),
                contactInfo == null ? null : normalize(contactInfo.getSecondaryEmail()));
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
            String legalName) {
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

        if (hasAmbiguousLegalNameMatch(employeeId, legalName)) {
            warnings.add("Ambiguous duplicate match detected by legalName similarity");
        }

        return warnings;
    }

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
                && emailService.findPersonIdsByPrimaryEmail(primaryEmail).stream()
                        .anyMatch(id -> employeeId == null || !id.equals(employeeId));

        boolean duplicatePhone = hasDuplicatePhone(employeeId, primaryPhone, secondaryPhone);
        return new DuplicateSignals(duplicateEmployeeNumber, duplicatePrimaryEmail, duplicatePhone);
    }

    private boolean hasAmbiguousLegalNameMatch(UUID employeeId, String legalName) {
        if (legalName == null || legalName.isBlank()) {
            return false;
        }
        return personRepository.findByLegalNameIgnoreCase(legalName).stream()
                .anyMatch(person -> employeeId == null || !person.getId().equals(employeeId));
    }

    /**
     * Keep the typed {@code firstName}/{@code lastName} columns in sync with {@code legalName} so a
     * person created or edited through the Employee path also surfaces a name in the directory and
     * the people typeahead (which read first/last). The first whitespace-delimited token becomes the
     * first name and the remainder the last name — a heuristic split, but it keeps both views
     * consistent. The structured {@code legalName} remains the authoritative value.
     */
    private void applyStructuredName(Person entity, String legalName) {
        if (legalName == null || legalName.isBlank()) {
            return;
        }
        String trimmed = legalName.trim();
        int firstSpace = trimmed.indexOf(' ');
        if (firstSpace < 0) {
            entity.setFirstName(trimmed);
            entity.setLastName("");
        } else {
            entity.setFirstName(trimmed.substring(0, firstSpace));
            entity.setLastName(trimmed.substring(firstSpace + 1).trim());
        }
    }

    /** Person identity: legal/preferred names, derived first/last. */
    private void applyIdentity(
            Person entity, String legalName, String preferredName, EmployeeContactInfoDto contactInfo) {
        entity.setLegalName(legalName);
        entity.setPreferredName(preferredName);
        applyStructuredName(entity, legalName);
        // Email + phone are persisted as EMAIL / PHONE_WORK contact points after save (see
        // createEmployee/updateEmployee); contactInfoJson is a read-only @Formula derived from
        // those contact points, so nothing is written here. (contactInfo is consumed by the
        // contact-point writes in the caller.)
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
        return java.util.stream.Stream.of(
                        normalize(contactInfo.getPrimaryPhone()), normalize(contactInfo.getSecondaryPhone()))
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
        return workPhoneService.findPersonIdsByWorkPhone(phoneValue).stream()
                .anyMatch(personId -> employeeId == null || !personId.equals(employeeId));
    }

    private EmployeeProfileDto toEmployeeProfile(Person person, Employee employee, List<String> warnings) {
        return EmployeeProfileDto.builder()
                .id(person.getId())
                .legalName(resolveLegalName(person))
                .preferredName(person.getPreferredName())
                .employeeNumber(employee != null ? employee.getEmployeeNumber() : null)
                .status(employee != null ? employee.getStatus() : null)
                .hireDate(employee != null ? employee.getHireDate() : null)
                .terminationDate(employee != null ? employee.getTerminationDate() : null)
                .contactInfo(resolveContactInfo(person))
                .statusEffectiveAt(employee != null ? employee.getStatusEffectiveAt() : null)
                .createdAt(person.getCreatedAt())
                .updatedAt(person.getUpdatedAt())
                .warnings(warnings)
                .build();
    }

    /**
     * The employee profile historically read {@code legalName}, but persons created via the
     * People path (and seed data) populate only {@code firstName}/{@code lastName}. Fall back to
     * the composed first/last name so the profile is never blank for those records.
     */
    private String resolveLegalName(Person person) {
        String legalName = person.getLegalName();
        if (legalName != null && !legalName.isBlank()) {
            return legalName;
        }
        String composed = java.util.stream.Stream.of(person.getFirstName(), person.getLastName())
                .filter(value -> value != null && !value.isBlank())
                .collect(java.util.stream.Collectors.joining(" "));
        return composed.isBlank() ? legalName : composed;
    }

    /**
     * Prefer the structured {@code contactInfoJson} blob, but fall back to the typed contact
     * columns ({@code primaryEmail}/{@code secondaryEmail}/{@code phoneNumbers}) when the blob is
     * absent — as it is for column-sourced persons (People path and seed data).
     */
    private EmployeeContactInfoDto resolveContactInfo(Person person) {
        EmployeeContactInfoDto fromJson = readContactInfo(person.getContactInfoJson());
        if (fromJson != null) {
            return fromJson;
        }
        return contactInfoFromColumns(person);
    }

    private EmployeeContactInfoDto contactInfoFromColumns(Person person) {
        PersonEmailService.EmailPair emails = emailService.getEmails(person.getId());
        String primaryEmail = normalize(emails.primary());
        String secondaryEmail = normalize(emails.secondary());
        List<String> phones = workPhoneService.getWorkPhones(person.getId());
        String primaryPhone = phones.size() > 0 ? normalize(phones.get(0)) : null;
        String secondaryPhone = phones.size() > 1 ? normalize(phones.get(1)) : null;

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

    private EmployeeContactInfoDto readContactInfo(String contactInfoJson) {
        if (contactInfoJson == null || contactInfoJson.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(contactInfoJson, EmployeeContactInfoDto.class);
        } catch (JsonProcessingException exception) {
            log.warn("Failed to deserialize contact info JSON. Returning null. reason={}", exception.getMessage());
            return null;
        }
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
        retry.setEmployee(personRepository.getReferenceById(employeeId));
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
