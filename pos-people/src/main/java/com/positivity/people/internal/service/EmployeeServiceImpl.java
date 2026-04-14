package com.positivity.people.internal.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.people.internal.dto.CreateEmployeeRequest;
import com.positivity.people.internal.dto.DisableEmployeeRequestDto;
import com.positivity.people.internal.dto.EmployeeContactInfoDto;
import com.positivity.people.internal.dto.EmployeeProfileDto;
import com.positivity.people.internal.dto.UpdateEmployeeRequest;
import com.positivity.people.internal.entity.EmployeeOffboardingRetry;
import com.positivity.people.internal.entity.Person;
import com.positivity.people.internal.enums.AssignmentTerminationPolicy;
import com.positivity.people.internal.enums.DuplicatePolicy;
import com.positivity.people.internal.enums.EmployeeStatus;
import com.positivity.people.internal.exception.PersonNotFoundException;
import com.positivity.people.internal.exception.SemanticValidationException;
import com.positivity.people.internal.repository.EmployeeOffboardingRetryRepository;
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

        Person entity = new Person();
        applyEmployeeFields(
                entity,
                new EmployeeFieldSet(
                        request.getLegalName(),
                        request.getPreferredName(),
                        request.getEmployeeNumber(),
                        request.getStatus(),
                        request.getHireDate(),
                        request.getTerminationDate(),
                        request.getContactInfo()));

        Person saved = personRepository.save(entity);
        return toEmployeeProfile(saved, warnings);
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull EmployeeProfileDto getEmployee(@NonNull UUID employeeId) {
        Person entity =
                personRepository.findById(employeeId).orElseThrow(() -> new PersonNotFoundException(employeeId));
        return toEmployeeProfile(entity, List.of());
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto updateEmployee(
            @NonNull UUID employeeId, @NonNull UpdateEmployeeRequest request) {
        validateEmployeeRequest(request.getHireDate(), request.getTerminationDate());

        Person entity =
                personRepository.findById(employeeId).orElseThrow(() -> new PersonNotFoundException(employeeId));

        List<String> warnings = evaluateDuplicatePolicy(
                employeeId,
                request.getDuplicatePolicy(),
                request.getEmployeeNumber(),
                request.getContactInfo(),
                request.getLegalName());

        EmployeeStatus previousStatus = entity.getStatus();
        applyEmployeeFields(
                entity,
                new EmployeeFieldSet(
                        request.getLegalName(),
                        request.getPreferredName(),
                        request.getEmployeeNumber(),
                        request.getStatus(),
                        request.getHireDate(),
                        request.getTerminationDate(),
                        request.getContactInfo()));

        if (previousStatus != request.getStatus()) {
            entity.setStatusEffectiveAt(Instant.now(clock));
        }

        Person saved = personRepository.save(entity);
        return toEmployeeProfile(saved, warnings);
    }

    @Override
    @Transactional
    public @NonNull EmployeeProfileDto disableEmployee(
            @NonNull UUID employeeId, @NonNull DisableEmployeeRequestDto request) {
        Person entity =
                personRepository.findById(employeeId).orElseThrow(() -> new PersonNotFoundException(employeeId));

        EmployeeStatus currentStatus = entity.getStatus();
        if (currentStatus == EmployeeStatus.DISABLED || currentStatus == EmployeeStatus.TERMINATED) {
            throw new IllegalArgumentException("Employee is already DISABLED or TERMINATED");
        }
        if (currentStatus != EmployeeStatus.ACTIVE) {
            throw new IllegalArgumentException("Only ACTIVE employees can be disabled");
        }

        entity.setStatus(EmployeeStatus.DISABLED);
        entity.setStatusEffectiveAt(Instant.now(clock));
        Person saved = personRepository.save(entity);

        String actorId = SecurityContextHelper.getCurrentUsernameOrDefault(SYSTEM_ACTOR);
        try {
            applyAssignmentPolicy(saved, request, actorId);
        } catch (Exception exception) {
            log.warn(
                    "Offboarding downstream action failed for employee {}. Queuing retry. Reason: {}",
                    employeeId,
                    exception.getMessage());
            queueOffboardingRetry(saved.getId(), request, actorId, exception.getMessage());
        }

        return toEmployeeProfile(saved, List.of());
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

        boolean duplicateEmployeeNumber = employeeId == null
                ? personRepository.existsByEmployeeNumberIgnoreCase(employeeNumber)
                : personRepository.existsByEmployeeNumberIgnoreCaseAndIdNot(employeeNumber, employeeId);

        boolean duplicatePrimaryEmail = primaryEmail != null
                && (employeeId == null
                        ? personRepository.existsByPrimaryEmailIgnoreCase(primaryEmail)
                        : personRepository.existsByPrimaryEmailIgnoreCaseAndIdNot(primaryEmail, employeeId));

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

    private void applyEmployeeFields(Person entity, EmployeeFieldSet fields) {
        String legalName = fields.legalName();
        String preferredName = fields.preferredName();
        String employeeNumber = fields.employeeNumber();
        EmployeeStatus status = fields.status();
        java.time.LocalDate hireDate = fields.hireDate();
        java.time.LocalDate terminationDate = fields.terminationDate();
        EmployeeContactInfoDto contactInfo = fields.contactInfo();

        entity.setLegalName(legalName);
        entity.setPreferredName(preferredName);
        entity.setEmployeeNumber(employeeNumber);
        entity.setStatus(status);
        entity.setHireDate(hireDate);
        entity.setTerminationDate(terminationDate);

        if (entity.getStatusEffectiveAt() == null) {
            entity.setStatusEffectiveAt(Instant.now(clock));
        }

        if (contactInfo != null) {
            entity.setPrimaryEmail(normalize(contactInfo.getPrimaryEmail()));
            entity.setSecondaryEmail(normalize(contactInfo.getSecondaryEmail()));

            List<String> phones = java.util.stream.Stream.of(
                            normalize(contactInfo.getPrimaryPhone()), normalize(contactInfo.getSecondaryPhone()))
                    .filter(value -> value != null && !value.isBlank())
                    .toList();
            entity.setPhoneNumbers(new ArrayList<>(phones));
            entity.setContactInfoJson(writeContactInfo(contactInfo));
        } else {
            entity.setContactInfoJson(null);
            entity.setPhoneNumbers(new ArrayList<>());
        }
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
        return personRepository.findByPhoneNumbersContains(phoneValue).stream()
                .anyMatch(person -> employeeId == null || !person.getId().equals(employeeId));
    }

    private EmployeeProfileDto toEmployeeProfile(Person person, List<String> warnings) {
        return EmployeeProfileDto.builder()
                .id(person.getId())
                .legalName(person.getLegalName())
                .preferredName(person.getPreferredName())
                .employeeNumber(person.getEmployeeNumber())
                .status(person.getStatus())
                .hireDate(person.getHireDate())
                .terminationDate(person.getTerminationDate())
                .contactInfo(readContactInfo(person.getContactInfoJson()))
                .statusEffectiveAt(person.getStatusEffectiveAt())
                .warnings(warnings)
                .build();
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

    private String writeContactInfo(EmployeeContactInfoDto contactInfo) {
        try {
            return objectMapper.writeValueAsString(contactInfo);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to persist contactInfo", exception);
        }
    }

    private void applyAssignmentPolicy(Person employee, DisableEmployeeRequestDto request, String actorId) {
        AssignmentTerminationPolicy policy = request.getAssignmentPolicy() != null
                ? request.getAssignmentPolicy()
                : AssignmentTerminationPolicy.IMMEDIATE;

        switch (policy) {
            case IMMEDIATE ->
                log.info(
                        "Applying IMMEDIATE assignment offboarding for employee {} by actor {}",
                        employee.getId(),
                        actorId);
            case GRACE_PERIOD ->
                log.info(
                        "Applying GRACE_PERIOD assignment offboarding for employee {} with assignmentEndDate {} by actor {}",
                        employee.getId(),
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

    private record EmployeeFieldSet(
            String legalName,
            String preferredName,
            String employeeNumber,
            EmployeeStatus status,
            java.time.LocalDate hireDate,
            java.time.LocalDate terminationDate,
            EmployeeContactInfoDto contactInfo) {}

    private record DuplicateSignals(
            boolean duplicateEmployeeNumber, boolean duplicatePrimaryEmail, boolean duplicatePhone) {
        private boolean hasAny() {
            return duplicateEmployeeNumber || duplicatePrimaryEmail || duplicatePhone;
        }
    }
}
