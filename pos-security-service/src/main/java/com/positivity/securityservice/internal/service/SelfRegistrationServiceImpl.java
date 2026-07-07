package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.client.CustomerRegistrationClient;
import com.positivity.securityservice.internal.client.PeopleRegistrationClient;
import com.positivity.securityservice.internal.client.dto.CustomerPersonSearchResponse;
import com.positivity.securityservice.internal.client.dto.PeopleLinkUserRequest;
import com.positivity.securityservice.internal.client.dto.PeopleResolvePersonRequest;
import com.positivity.securityservice.internal.client.dto.PeopleResolvePersonResponse;
import com.positivity.securityservice.internal.dto.CrmMatchSummaryDto;
import com.positivity.securityservice.internal.dto.SelfRegistrationRequest;
import com.positivity.securityservice.internal.dto.SelfRegistrationResponse;
import com.positivity.securityservice.internal.dto.SelfRegistrationReviewCaseCreateRequest;
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.SelfRegistrationAttempt;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.enums.SelfRegistrationAttemptStatus;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import com.positivity.securityservice.internal.exception.SelfRegistrationConflictException;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.SelfRegistrationReviewService;
import com.positivity.securityservice.service.SelfRegistrationService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SelfRegistrationServiceImpl implements SelfRegistrationService {

    private static final String USER_PERSON_LINK_CONFLICT = "USER_PERSON_LINK_CONFLICT";
    private static final String DEFAULT_SELF_REGISTRATION_ROLE = "SELF_SERVICE_CUSTOMER";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PeopleRegistrationClient peopleRegistrationClient;
    private final CustomerRegistrationClient customerRegistrationClient;
    private final SelfRegistrationAttemptService selfRegistrationAttemptService;
    private final SelfRegistrationReviewService selfRegistrationReviewService;

    @Override
    @Transactional
    public @NonNull SelfRegistrationResponse selfRegister(@NonNull SelfRegistrationRequest request) {
        String normalizedEmail = normalizeEmail(request.email());
        String normalizedPhone = normalizePhone(request.phone());
        String normalizedFirstName = normalizeName(request.firstName());
        String normalizedLastName = normalizeName(request.lastName());
        String explicitUsername = normalizeUsername(request.username());
        String derivedUsername = deriveUsernameFromEmail(normalizedEmail);
        String chosenUsername = explicitUsername != null ? explicitUsername : derivedUsername;
        String normalizedIdpSubject = normalizeOptionalText(request.idpSubject());
        String idempotencyKey = normalizeIdempotencyKey(request.idempotencyKey());
        String requestFingerprint = buildRequestFingerprint(
                normalizedEmail,
                normalizedPhone,
                normalizedFirstName,
                normalizedLastName,
                explicitUsername,
                normalizedIdpSubject);

        if (idempotencyKey != null) {
            Optional<SelfRegistrationAttempt> existingAttempt =
                    selfRegistrationAttemptService.findByIdempotencyKey(idempotencyKey);
            if (existingAttempt.isPresent()) {
                return replayAttempt(existingAttempt.get(), requestFingerprint);
            }
        }

        try {
            SelfRegistrationResponse response = doSelfRegister(
                    normalizedEmail,
                    normalizedPhone,
                    normalizedFirstName,
                    normalizedLastName,
                    chosenUsername,
                    derivedUsername,
                    request.password(),
                    idempotencyKey);
            if (idempotencyKey != null) {
                selfRegistrationAttemptService.recordSuccess(
                        idempotencyKey, requestFingerprint, normalizedEmail, chosenUsername, response);
            }
            return response;
        } catch (SelfRegistrationConflictException ex) {
            if (idempotencyKey != null) {
                selfRegistrationAttemptService.recordConflict(
                        idempotencyKey,
                        requestFingerprint,
                        normalizedEmail,
                        chosenUsername,
                        ex.getErrorCode(),
                        ex.getMessage(),
                        ex.getReferenceId());
            }
            throw ex;
        }
    }

    private SelfRegistrationResponse doSelfRegister(
            String normalizedEmail,
            String normalizedPhone,
            String normalizedFirstName,
            String normalizedLastName,
            String chosenUsername,
            String derivedUsername,
            String rawPassword,
            String idempotencyKey) {
        ensureUserDoesNotAlreadyExist(chosenUsername, derivedUsername, normalizedEmail);

        List<CustomerPersonSearchResponse> crmMatches = customerRegistrationClient.searchPersons(
                buildFullName(normalizedFirstName, normalizedLastName), normalizedEmail, normalizedPhone);
        CrmSignalAssessment crmSignalAssessment =
                assessCrmMatches(crmMatches, normalizedEmail, normalizedPhone, normalizedFirstName, normalizedLastName);

        PeopleResolvePersonResponse resolvedPerson =
                peopleRegistrationClient.resolvePerson(PeopleResolvePersonRequest.builder()
                        .email(normalizedEmail)
                        .phone(normalizedPhone)
                        .firstName(normalizedFirstName)
                        .lastName(normalizedLastName)
                        .build());
        enforceCrmConflictRules(resolvedPerson, crmSignalAssessment, normalizedEmail, chosenUsername);

        enforceLinkedUserRules(resolvedPerson.personId(), normalizedEmail, chosenUsername);

        User createdUser = createUser(chosenUsername, rawPassword, resolvedPerson.personId());
        try {
            peopleRegistrationClient.linkUserToPerson(PeopleLinkUserRequest.builder()
                    .userId(createdUser.getId())
                    .personId(resolvedPerson.personId())
                    .linkType("PRIMARY")
                    .notes("Created by self-registration")
                    .build());
        } catch (RuntimeException ex) {
            userRepository.deleteById(createdUser.getId());
            UUID referenceId = selfRegistrationReviewService.openCase(SelfRegistrationReviewCaseCreateRequest.builder()
                    .caseType(SelfRegistrationCaseType.IDENTITY_REVIEW)
                    .reasonCode(USER_PERSON_LINK_CONFLICT)
                    .reasonMessage("User was created but could not be linked to the resolved person")
                    .email(normalizedEmail)
                    .requestedUsername(chosenUsername)
                    .personId(resolvedPerson.personId())
                    .notes("User creation succeeded but link-to-person compensation was required")
                    .build());
            throw new SelfRegistrationConflictException(
                    USER_PERSON_LINK_CONFLICT,
                    "User was created but could not be linked to the resolved person",
                    referenceId);
        }

        return SelfRegistrationResponse.builder()
                .userId(createdUser.getId())
                .personId(resolvedPerson.personId())
                .username(createdUser.getUsername())
                .linkStatus("LINKED")
                .matchedExistingPerson(resolvedPerson.matchedExisting())
                .crmMatchSummary(crmSignalAssessment.summary())
                .idempotencyKey(idempotencyKey)
                .issuedTokens(false)
                .build();
    }

    private SelfRegistrationResponse replayAttempt(SelfRegistrationAttempt attempt, String requestFingerprint) {
        if (!requestFingerprint.equals(attempt.getRequestFingerprint())) {
            throw new SelfRegistrationConflictException(
                    "IDEMPOTENCY_KEY_REUSED",
                    "The provided idempotency key has already been used with a different self-registration request",
                    attempt.getReferenceId());
        }
        if (attempt.getStatus() == SelfRegistrationAttemptStatus.SUCCEEDED) {
            return SelfRegistrationResponse.builder()
                    .userId(attempt.getUserId())
                    .personId(attempt.getPersonId())
                    .username(attempt.getUsername())
                    .linkStatus(attempt.getLinkStatus())
                    .matchedExistingPerson(attempt.isMatchedExistingPerson())
                    .crmMatchSummary(toCrmMatchSummary(attempt))
                    .idempotencyKey(attempt.getIdempotencyKey())
                    .issuedTokens(attempt.isIssuedTokens())
                    .build();
        }
        throw new SelfRegistrationConflictException(
                attempt.getConflictCode(), attempt.getConflictMessage(), attempt.getReferenceId());
    }

    private CrmMatchSummaryDto toCrmMatchSummary(SelfRegistrationAttempt attempt) {
        if (attempt.getCrmCandidateCount() == null) {
            return null;
        }
        return CrmMatchSummaryDto.builder()
                .candidateCount(attempt.getCrmCandidateCount())
                .anyMatches(Boolean.TRUE.equals(attempt.getCrmAnyMatches()))
                .individualCustomerCandidateCount(defaultInteger(attempt.getCrmIndividualCustomerCandidateCount()))
                .commercialContactCandidateCount(defaultInteger(attempt.getCrmCommercialContactCandidateCount()))
                .sharedIdentityCandidateCount(defaultInteger(attempt.getCrmSharedIdentityCandidateCount()))
                .exactEmailMatch(Boolean.TRUE.equals(attempt.getCrmExactEmailMatch()))
                .exactPhoneMatch(Boolean.TRUE.equals(attempt.getCrmExactPhoneMatch()))
                .exactNameMatch(Boolean.TRUE.equals(attempt.getCrmExactNameMatch()))
                .reviewRequired(Boolean.TRUE.equals(attempt.getCrmReviewRequired()))
                .build();
    }

    private void ensureUserDoesNotAlreadyExist(String chosenUsername, String derivedUsername, String normalizedEmail) {
        Set<String> usernamesToCheck = new LinkedHashSet<>();
        usernamesToCheck.add(chosenUsername);
        usernamesToCheck.add(derivedUsername);

        for (String username : usernamesToCheck) {
            if (username == null || username.isBlank()) {
                continue;
            }
            Optional<User> existing = userRepository.findByUsername(username);
            if (existing.isEmpty()) {
                continue;
            }
            if (isActive(existing.get())) {
                throw new SelfRegistrationConflictException(
                        "USER_ALREADY_EXISTS", "A user account already exists for username " + username);
            }
            UUID referenceId = selfRegistrationReviewService.openCase(SelfRegistrationReviewCaseCreateRequest.builder()
                    .caseType(SelfRegistrationCaseType.ACCOUNT_RECOVERY)
                    .reasonCode("ACCOUNT_RECOVERY_REQUIRED")
                    .reasonMessage(
                            "An existing inactive account must be recovered instead of creating a second account")
                    .email(normalizedEmail)
                    .requestedUsername(username)
                    .personId(existing.get().getPersonId())
                    .linkedUserId(existing.get().getId())
                    .notes("Self-registration matched an inactive existing username")
                    .build());
            throw new SelfRegistrationConflictException(
                    "ACCOUNT_RECOVERY_REQUIRED",
                    "An existing inactive account must be recovered instead of creating a second account",
                    referenceId);
        }
    }

    private void enforceLinkedUserRules(UUID personId, String normalizedEmail, String chosenUsername) {
        List<UUID> linkedUserIds = peopleRegistrationClient.getLinkedUserIds(personId);
        if (linkedUserIds.isEmpty()) {
            return;
        }
        UUID linkedUserId = linkedUserIds.get(0);
        User linkedUser = userRepository
                .findById(linkedUserId)
                .orElseThrow(() -> new SelfRegistrationConflictException(
                        USER_PERSON_LINK_CONFLICT,
                        "Resolved person has an inconsistent existing user link",
                        selfRegistrationReviewService.openCase(SelfRegistrationReviewCaseCreateRequest.builder()
                                .caseType(SelfRegistrationCaseType.IDENTITY_REVIEW)
                                .reasonCode(USER_PERSON_LINK_CONFLICT)
                                .reasonMessage("Resolved person has an inconsistent existing user link")
                                .email(normalizedEmail)
                                .requestedUsername(chosenUsername)
                                .personId(personId)
                                .linkedUserId(linkedUserId)
                                .notes("People link exists but security user record could not be found")
                                .build())));
        if (isActive(linkedUser)) {
            throw new SelfRegistrationConflictException(
                    "PERSON_ALREADY_HAS_ACTIVE_USER", "Resolved person is already linked to a different active user");
        }
        UUID referenceId = selfRegistrationReviewService.openCase(SelfRegistrationReviewCaseCreateRequest.builder()
                .caseType(SelfRegistrationCaseType.ACCOUNT_RECOVERY)
                .reasonCode("ACCOUNT_RECOVERY_REQUIRED")
                .reasonMessage("Resolved person already has an inactive linked user and must go through recovery")
                .email(normalizedEmail)
                .requestedUsername(chosenUsername)
                .personId(personId)
                .linkedUserId(linkedUserId)
                .notes("Inactive linked user blocks self-registration")
                .build());
        throw new SelfRegistrationConflictException(
                "ACCOUNT_RECOVERY_REQUIRED",
                "Resolved person already has an inactive linked user and must go through recovery",
                referenceId);
    }

    private void enforceCrmConflictRules(
            PeopleResolvePersonResponse resolvedPerson,
            CrmSignalAssessment crmSignalAssessment,
            String normalizedEmail,
            String chosenUsername) {
        if (resolvedPerson.matchedExisting() || !crmSignalAssessment.reviewRequired()) {
            return;
        }
        UUID referenceId = selfRegistrationReviewService.openCase(SelfRegistrationReviewCaseCreateRequest.builder()
                .caseType(SelfRegistrationCaseType.IDENTITY_REVIEW)
                .reasonCode("CRM_PERSON_CONFLICT")
                .reasonMessage(buildCrmConflictMessage(crmSignalAssessment.summary()))
                .email(normalizedEmail)
                .requestedUsername(chosenUsername)
                .personId(resolvedPerson.personId())
                .crmMatchSummary(crmSignalAssessment.summary())
                .notes("CRM indicated an existing human identity that should be reviewed before creating a new account")
                .build());
        peopleRegistrationClient.deletePerson(resolvedPerson.personId());
        throw new SelfRegistrationConflictException(
                "CRM_PERSON_CONFLICT", buildCrmConflictMessage(crmSignalAssessment.summary()), referenceId);
    }

    private CrmSignalAssessment assessCrmMatches(
            List<CustomerPersonSearchResponse> crmMatches,
            String normalizedEmail,
            String normalizedPhone,
            String normalizedFirstName,
            String normalizedLastName) {
        boolean exactEmailMatch = crmMatches.stream().anyMatch(match -> hasMatchingEmail(match, normalizedEmail));
        boolean exactPhoneMatch = normalizedPhone != null
                && crmMatches.stream().anyMatch(match -> hasMatchingPhone(match, normalizedPhone));
        boolean exactNameMatch =
                crmMatches.stream().anyMatch(match -> hasMatchingName(match, normalizedFirstName, normalizedLastName));

        int individualCustomerCandidateCount = Math.toIntExact(crmMatches.stream()
                .filter(CustomerPersonSearchResponse::individualCustomer)
                .count());
        int commercialContactCandidateCount = Math.toIntExact(crmMatches.stream()
                .filter(CustomerPersonSearchResponse::commercialContact)
                .count());
        int sharedIdentityCandidateCount = Math.toIntExact(crmMatches.stream()
                .filter(match -> match.individualCustomer() && match.commercialContact())
                .count());

        boolean reviewRequired = !crmMatches.isEmpty()
                && (exactEmailMatch || (exactPhoneMatch && exactNameMatch) || sharedIdentityCandidateCount > 0);

        CrmMatchSummaryDto summary = CrmMatchSummaryDto.builder()
                .candidateCount(crmMatches.size())
                .anyMatches(!crmMatches.isEmpty())
                .individualCustomerCandidateCount(individualCustomerCandidateCount)
                .commercialContactCandidateCount(commercialContactCandidateCount)
                .sharedIdentityCandidateCount(sharedIdentityCandidateCount)
                .exactEmailMatch(exactEmailMatch)
                .exactPhoneMatch(exactPhoneMatch)
                .exactNameMatch(exactNameMatch)
                .reviewRequired(reviewRequired)
                .build();
        return new CrmSignalAssessment(summary);
    }

    private boolean hasMatchingEmail(CustomerPersonSearchResponse match, String normalizedEmail) {
        return match.contactPoints().stream()
                .filter(contactPoint -> "EMAIL".equalsIgnoreCase(contactPoint.contactType()))
                .map(contactPoint -> normalizeContactValue(contactPoint.value(), false))
                .anyMatch(normalizedEmail::equals);
    }

    private boolean hasMatchingPhone(CustomerPersonSearchResponse match, String normalizedPhone) {
        return match.contactPoints().stream()
                .filter(contactPoint -> contactPoint.contactType() != null
                        && contactPoint.contactType().toUpperCase(Locale.ROOT).startsWith("PHONE"))
                .map(contactPoint -> normalizeContactValue(contactPoint.value(), true))
                .anyMatch(normalizedPhone::equals);
    }

    private boolean hasMatchingName(
            CustomerPersonSearchResponse match, String normalizedFirstName, String normalizedLastName) {
        String matchFirstName = normalizeOptionalText(match.firstName());
        String matchLastName = normalizeOptionalText(match.lastName());
        if (normalizedFirstName.equals(matchFirstName) && normalizedLastName.equals(matchLastName)) {
            return true;
        }
        String matchDisplayName = normalizeOptionalText(match.displayName());
        return matchDisplayName != null
                && matchDisplayName.equals((normalizedFirstName + " " + normalizedLastName).trim());
    }

    private String buildCrmConflictMessage(CrmMatchSummaryDto summary) {
        String identityContext;
        if (summary.getSharedIdentityCandidateCount() > 0) {
            identityContext =
                    "matching CRM records show the person is both an individual customer and a commercial contact";
        } else if (summary.getCommercialContactCandidateCount() > 0) {
            identityContext = "matching CRM records show the person is already a commercial contact";
        } else {
            identityContext = "matching CRM records indicate an existing customer identity";
        }
        return "Registration was blocked because " + identityContext
                + ". Please use account recovery or contact support for review.";
    }

    private User createUser(String username, String password, UUID personId) {
        Role defaultRole = roleRepository
                .findByName(DEFAULT_SELF_REGISTRATION_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "Required self-registration role not found: " + DEFAULT_SELF_REGISTRATION_ROLE));
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setPersonId(personId);
        user.setRoles(Set.of(defaultRole));
        return userRepository.save(user);
    }

    private boolean isActive(User user) {
        return user.isEnabled()
                && user.isAccountNonLocked()
                && user.isAccountNonExpired()
                && user.isCredentialsNonExpired();
    }

    private String normalizeEmail(String email) {
        String normalized = normalizeRequired(email, "email");
        return normalized.toLowerCase(Locale.ROOT);
    }

    private String normalizeName(String value) {
        return normalizeRequired(value, "name");
    }

    private String normalizeIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return null;
        }
        return idempotencyKey.trim();
    }

    private String normalizeOptionalText(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRequired(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
        return value.trim();
    }

    private String normalizeUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return username.trim().toLowerCase(Locale.ROOT);
    }

    private String deriveUsernameFromEmail(String email) {
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            throw new IllegalArgumentException("email must contain a local part");
        }
        return email.substring(0, atIndex).toLowerCase(Locale.ROOT);
    }

    private String normalizePhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return null;
        }
        String trimmed = phone.trim();
        String digits = trimmed.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return null;
        }
        return trimmed.startsWith("+") ? "+" + digits : digits;
    }

    private String normalizeContactValue(String value, boolean phone) {
        return phone ? normalizePhone(value) : normalizeOptionalText(value);
    }

    private String buildFullName(String firstName, String lastName) {
        return (firstName + " " + lastName).trim();
    }

    private String buildRequestFingerprint(
            String normalizedEmail,
            String normalizedPhone,
            String normalizedFirstName,
            String normalizedLastName,
            String explicitUsername,
            String normalizedIdpSubject) {
        String canonical = String.join(
                "|",
                normalizedEmail,
                defaultString(normalizedPhone),
                normalizedFirstName,
                normalizedLastName,
                defaultString(explicitUsername),
                defaultString(normalizedIdpSubject));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to create self-registration fingerprint", ex);
        }
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private int defaultInteger(Integer value) {
        return value == null ? 0 : value;
    }

    private record CrmSignalAssessment(CrmMatchSummaryDto summary) {
        private boolean reviewRequired() {
            return summary.isReviewRequired();
        }
    }
}
