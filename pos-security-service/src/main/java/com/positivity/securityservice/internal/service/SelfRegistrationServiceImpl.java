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
import com.positivity.securityservice.internal.entity.Role;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.exception.SelfRegistrationConflictException;
import com.positivity.securityservice.internal.repository.RoleRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import com.positivity.securityservice.service.SelfRegistrationService;
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

    private static final String DEFAULT_SELF_REGISTRATION_ROLE = "SELF_SERVICE_CUSTOMER";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PeopleRegistrationClient peopleRegistrationClient;
    private final CustomerRegistrationClient customerRegistrationClient;

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

        ensureUserDoesNotAlreadyExist(chosenUsername, derivedUsername);

        List<CustomerPersonSearchResponse> crmMatches = customerRegistrationClient.searchPersons(
                buildFullName(normalizedFirstName, normalizedLastName),
                normalizedEmail,
                normalizedPhone);
        CrmSignalAssessment crmSignalAssessment = assessCrmMatches(
                crmMatches,
                normalizedEmail,
                normalizedPhone,
                normalizedFirstName,
                normalizedLastName);

        PeopleResolvePersonResponse resolvedPerson = peopleRegistrationClient.resolvePerson(PeopleResolvePersonRequest.builder()
                .email(normalizedEmail)
                .phone(normalizedPhone)
                .firstName(normalizedFirstName)
                .lastName(normalizedLastName)
                .build());
        enforceCrmConflictRules(resolvedPerson, crmSignalAssessment);

        enforceLinkedUserRules(resolvedPerson.personId());

        User createdUser = createUser(chosenUsername, request.password(), resolvedPerson.personId());
        try {
            peopleRegistrationClient.linkUserToPerson(PeopleLinkUserRequest.builder()
                    .userId(createdUser.getId())
                    .personId(resolvedPerson.personId())
                    .linkType("PRIMARY")
                    .notes("Created by self-registration")
                    .build());
        } catch (RuntimeException ex) {
            userRepository.deleteById(createdUser.getId());
            throw new SelfRegistrationConflictException(
                    "USER_PERSON_LINK_CONFLICT",
                    "User was created but could not be linked to the resolved person");
        }

        return SelfRegistrationResponse.builder()
                .userId(createdUser.getId())
                .personId(resolvedPerson.personId())
                .username(createdUser.getUsername())
                .linkStatus("LINKED")
                .matchedExistingPerson(resolvedPerson.matchedExisting())
                .crmMatchSummary(crmSignalAssessment.summary())
                .issuedTokens(false)
                .build();
    }

    private void ensureUserDoesNotAlreadyExist(String chosenUsername, String derivedUsername) {
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
                        "USER_ALREADY_EXISTS",
                        "A user account already exists for username " + username);
            }
            throw new SelfRegistrationConflictException(
                    "ACCOUNT_RECOVERY_REQUIRED",
                    "An existing inactive account must be recovered instead of creating a second account");
        }
    }

    private void enforceLinkedUserRules(UUID personId) {
        List<UUID> linkedUserIds = peopleRegistrationClient.getLinkedUserIds(personId);
        for (UUID linkedUserId : linkedUserIds) {
            User linkedUser = userRepository.findById(linkedUserId)
                    .orElseThrow(() -> new SelfRegistrationConflictException(
                            "USER_PERSON_LINK_CONFLICT",
                            "Resolved person has an inconsistent existing user link"));
            if (isActive(linkedUser)) {
                throw new SelfRegistrationConflictException(
                        "PERSON_ALREADY_HAS_ACTIVE_USER",
                        "Resolved person is already linked to a different active user");
            }
            throw new SelfRegistrationConflictException(
                    "ACCOUNT_RECOVERY_REQUIRED",
                    "Resolved person already has an inactive linked user and must go through recovery");
        }
    }

    private void enforceCrmConflictRules(
            PeopleResolvePersonResponse resolvedPerson,
            CrmSignalAssessment crmSignalAssessment) {
        if (resolvedPerson.matchedExisting() || !crmSignalAssessment.reviewRequired()) {
            return;
        }
        peopleRegistrationClient.deletePerson(resolvedPerson.personId());
        throw new SelfRegistrationConflictException(
                "CRM_PERSON_CONFLICT",
                buildCrmConflictMessage(crmSignalAssessment.summary()));
    }

    private CrmSignalAssessment assessCrmMatches(
            List<CustomerPersonSearchResponse> crmMatches,
            String normalizedEmail,
            String normalizedPhone,
            String normalizedFirstName,
            String normalizedLastName) {
        boolean exactEmailMatch = crmMatches.stream()
                .anyMatch(match -> hasMatchingEmail(match, normalizedEmail));
        boolean exactPhoneMatch = normalizedPhone != null && crmMatches.stream()
                .anyMatch(match -> hasMatchingPhone(match, normalizedPhone));
        boolean exactNameMatch = crmMatches.stream()
                .anyMatch(match -> hasMatchingName(match, normalizedFirstName, normalizedLastName));

        int individualCustomerCandidateCount = (int) crmMatches.stream()
                .filter(CustomerPersonSearchResponse::individualCustomer)
                .count();
        int commercialContactCandidateCount = (int) crmMatches.stream()
                .filter(CustomerPersonSearchResponse::commercialContact)
                .count();
        int sharedIdentityCandidateCount = (int) crmMatches.stream()
                .filter(match -> match.individualCustomer() && match.commercialContact())
                .count();

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
            CustomerPersonSearchResponse match,
            String normalizedFirstName,
            String normalizedLastName) {
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
            identityContext = "matching CRM records show the person is both an individual customer and a commercial contact";
        } else if (summary.getCommercialContactCandidateCount() > 0) {
            identityContext = "matching CRM records show the person is already a commercial contact";
        } else {
            identityContext = "matching CRM records indicate an existing customer identity";
        }
        return "Registration was blocked because " + identityContext
                + ". Please use account recovery or contact support for review.";
    }

    private User createUser(String username, String password, UUID personId) {
        Role defaultRole = roleRepository.findByName(DEFAULT_SELF_REGISTRATION_ROLE)
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

    private record CrmSignalAssessment(CrmMatchSummaryDto summary) {
        private boolean reviewRequired() {
            return summary.isReviewRequired();
        }
    }
}
