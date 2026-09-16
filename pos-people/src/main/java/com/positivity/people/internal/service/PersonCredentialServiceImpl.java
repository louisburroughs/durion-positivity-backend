package com.positivity.people.internal.service;

import com.positivity.people.internal.config.PeopleEventPublisher;
import com.positivity.people.internal.dto.CredentialUpsertCommand;
import com.positivity.people.internal.dto.PersonCredentialResponse;
import com.positivity.people.internal.entity.PersonCredential;
import com.positivity.people.internal.entity.Skill;
import com.positivity.people.internal.enums.CredentialStatus;
import com.positivity.people.internal.exception.NotFoundException;
import com.positivity.people.internal.exception.RequestValidationException;
import com.positivity.people.internal.repository.EmployeeRepository;
import com.positivity.people.internal.repository.PersonCredentialRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class PersonCredentialServiceImpl implements PersonCredentialService {

    private final PersonCredentialRepository repository;
    private final EmployeeRepository employeeRepository;
    private final SkillRegistryService skillRegistryService;
    private final PeopleEventPublisher peopleEventPublisher;
    private final Clock clock;

    @Override
    @Transactional
    public @NonNull PersonCredentialResponse upsert(
            @NonNull UUID personId, @NonNull CredentialUpsertCommand command, @NonNull String actor) {
        if (employeeRepository.findByPersonId(personId).isEmpty()) {
            throw new NotFoundException("No employee record for person " + personId);
        }
        Skill skill = resolveSkill(command);
        String issuer = normalizeIssuer(command, skill);
        if (command.getExpiresOn() != null && command.getExpiresOn().isBefore(command.getIssuedOn())) {
            throw new RequestValidationException("expiresOn must not be before issuedOn");
        }
        LocalDate today = LocalDate.now(clock);

        PersonCredential credential = repository
                .findByPersonIdAndSkill_IdAndIssuerAndIssuedOn(personId, skill.getId(), issuer, command.getIssuedOn())
                .orElseGet(() -> PersonCredential.builder()
                        .personId(personId)
                        .skill(skill)
                        .issuer(issuer)
                        .issuedOn(command.getIssuedOn())
                        .createdBy(actor)
                        .build());
        // The mutable facts follow the feed; the natural key never does — a renewal is a new row.
        credential.setSourceCode(command.getSourceCode() == null ? null : normalize(command.getSourceCode()));
        credential.setSourceCredentialCode(
                command.getSourceCredentialCode() == null ? null : normalize(command.getSourceCredentialCode()));
        credential.setExpiresOn(command.getExpiresOn());
        credential.setProficiency(command.getProficiency());
        credential.setEvidenceRef(command.getEvidenceRef());
        credential.setSourceSystem(command.getSourceSystem());
        credential.setSourceVersion(command.getSourceVersion());
        // Status is derived, never trusted from the feed; a revocation is the one thing a re-send
        // does not undo. A row the feed had stopped sending and now sends again is back.
        if (credential.getStatus() != CredentialStatus.REVOKED) {
            credential.setStatus(CredentialStatus.derive(credential.getIssuedOn(), credential.getExpiresOn(), today));
            credential.setSupersededBy(null);
        }
        PersonCredential saved = repository.save(credential);
        peopleEventPublisher.publishPersonCredentialUpdated(saved);
        return toResponse(saved, today);
    }

    @Override
    @Transactional
    public int supersedeAbsent(
            @NonNull UUID personId,
            @NonNull String sourceSystem,
            @NonNull Set<UUID> retainedIds,
            @NonNull String supersededBy) {
        int changed = 0;
        for (PersonCredential credential : repository.findByPersonIdAndSourceSystem(personId, sourceSystem)) {
            if (retainedIds.contains(credential.getId())
                    || credential.getStatus() == CredentialStatus.REVOKED
                    || credential.getStatus() == CredentialStatus.SUPERSEDED) {
                continue;
            }
            credential.setStatus(CredentialStatus.SUPERSEDED);
            credential.setSupersededBy(supersededBy);
            peopleEventPublisher.publishPersonCredentialUpdated(repository.save(credential));
            changed++;
        }
        if (changed > 0) {
            log.info("Superseded {} credential(s) for person {} no longer sent by {}", changed, personId, sourceSystem);
        }
        return changed;
    }

    @Override
    @Transactional(readOnly = true)
    public @NonNull List<PersonCredentialResponse> listByPerson(@NonNull UUID personId) {
        LocalDate today = LocalDate.now(clock);
        return repository.findByPersonIdOrderByIssuedOnDesc(personId).stream()
                .map(credential -> toResponse(credential, today))
                .toList();
    }

    private Skill resolveSkill(CredentialUpsertCommand command) {
        if (command.getSkillCode() != null && !command.getSkillCode().isBlank()) {
            return skillRegistryService.requireByCode(command.getSkillCode());
        }
        if (command.getSourceCode() == null
                || command.getSourceCode().isBlank()
                || command.getSourceCredentialCode() == null
                || command.getSourceCredentialCode().isBlank()) {
            throw new RequestValidationException(
                    "A credential names its skill either by skillCode or by sourceCode + sourceCredentialCode");
        }
        return skillRegistryService.resolve(command.getSourceCode(), command.getSourceCredentialCode());
    }

    private static String normalizeIssuer(CredentialUpsertCommand command, Skill skill) {
        if (command.getIssuer() != null && !command.getIssuer().isBlank()) {
            return normalize(command.getIssuer());
        }
        if (command.getSourceCode() != null && !command.getSourceCode().isBlank()) {
            return normalize(command.getSourceCode());
        }
        throw new RequestValidationException("issuer is required for a credential named by skillCode (" + skill.getCode() + ")");
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT);
    }

    static PersonCredentialResponse toResponse(PersonCredential credential, LocalDate onDate) {
        Skill skill = credential.getSkill();
        return PersonCredentialResponse.builder()
                .credentialId(credential.getId())
                .personId(credential.getPersonId())
                .skillId(skill.getId())
                .skillCode(skill.getCode())
                .competenceCode(skill.getCompetenceCode())
                .minGvwrClass(skill.getMinGvwrClass())
                .maxGvwrClass(skill.getMaxGvwrClass())
                .issuer(credential.getIssuer())
                .sourceCode(credential.getSourceCode())
                .sourceCredentialCode(credential.getSourceCredentialCode())
                .issuedOn(credential.getIssuedOn())
                .expiresOn(credential.getExpiresOn())
                .proficiency(credential.getProficiency())
                .status(credential.effectiveStatus(onDate).name())
                .evidenceRef(credential.getEvidenceRef())
                .build();
    }
}
