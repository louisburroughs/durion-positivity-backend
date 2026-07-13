package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.peoplecontact.UserPersonLinkCreateRequestedV1;
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
    private final PersonResolutionService personResolutionService;
    private final PeopleContactCommandEmitter peopleContactCommandEmitter;
    private final CrmSignalService crmSignalService;
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

        // CRM conflict signals from local replicas (ADR-0044 §6, #891): person candidates from
        // ext_people_contact_person, customer standing from ext_customer_person_identity.
        CrmSignalAssessment crmSignalAssessment = new CrmSignalAssessment(
                crmSignalService.assess(normalizedEmail, normalizedPhone, normalizedFirstName, normalizedLastName));

        // Person resolution is local (amended ADR-0043, #876): match against the identity replica
        // first, run every conflict rule, and only then create anything — so no compensation
        // (user delete / person delete) is ever needed.
        Optional<UUID> matchedPersonId = personResolutionService.match(
                normalizedEmail, normalizedPhone, normalizedFirstName, normalizedLastName);
        boolean matchedExisting = matchedPersonId.isPresent();
        enforceCrmConflictRules(matchedExisting, crmSignalAssessment, normalizedEmail, chosenUsername);
        matchedPersonId.ifPresent(personId -> enforceLinkedUserRules(personId, normalizedEmail, chosenUsername));

        UUID personId = matchedPersonId.orElseGet(() -> personResolutionService.createPerson(
                normalizedEmail, normalizedPhone, normalizedFirstName, normalizedLastName));

        // users.person_id is a projection written only from link facts (amended ADR-0043 §2):
        // the user row is created unlinked, the link travels as a command, and the confirming
        // fact sets the projection. Callers see linkStatus=PENDING until then.
        User createdUser = createUser(chosenUsername, rawPassword);
        peopleContactCommandEmitter.requestLinkCreate(new UserPersonLinkCreateRequestedV1(
                personId, createdUser.getUsername(), "PRIMARY", "Created by self-registration"));

        return SelfRegistrationResponse.builder()
                .userId(createdUser.getId())
                .personId(personId)
                .username(createdUser.getUsername())
                .linkStatus("PENDING")
                .matchedExistingPerson(matchedExisting)
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

    /**
     * Existing-link checks run against the local {@code users.person_id} projection — exactly the
     * data the retired {@code getLinkedUserIds} call surfaced, kept current by link facts.
     */
    private void enforceLinkedUserRules(UUID personId, String normalizedEmail, String chosenUsername) {
        Optional<User> linked = userRepository.findByPersonId(personId);
        if (linked.isEmpty()) {
            return;
        }
        User linkedUser = linked.get();
        UUID linkedUserId = linkedUser.getId();
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
            boolean matchedExisting,
            CrmSignalAssessment crmSignalAssessment,
            String normalizedEmail,
            String chosenUsername) {
        if (matchedExisting || !crmSignalAssessment.reviewRequired()) {
            return;
        }
        // The conflict is detected before any person is created, so there is nothing to
        // compensate (the old flow had to delete the just-resolved person here).
        UUID referenceId = selfRegistrationReviewService.openCase(SelfRegistrationReviewCaseCreateRequest.builder()
                .caseType(SelfRegistrationCaseType.IDENTITY_REVIEW)
                .reasonCode("CRM_PERSON_CONFLICT")
                .reasonMessage(buildCrmConflictMessage(crmSignalAssessment.summary()))
                .email(normalizedEmail)
                .requestedUsername(chosenUsername)
                .crmMatchSummary(crmSignalAssessment.summary())
                .notes("CRM indicated an existing human identity that should be reviewed before creating a new account")
                .build());
        throw new SelfRegistrationConflictException(
                "CRM_PERSON_CONFLICT", buildCrmConflictMessage(crmSignalAssessment.summary()), referenceId);
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

    private User createUser(String username, String password) {
        Role defaultRole = roleRepository
                .findByName(DEFAULT_SELF_REGISTRATION_ROLE)
                .orElseThrow(() -> new IllegalStateException(
                        "Required self-registration role not found: " + DEFAULT_SELF_REGISTRATION_ROLE));
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        // users.person_id is written only by the people-contact link-fact consumer (ADR-0043 §2).
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
