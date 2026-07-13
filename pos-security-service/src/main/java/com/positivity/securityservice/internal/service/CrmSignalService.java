package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.CrmMatchSummaryDto;
import com.positivity.securityservice.internal.entity.ExtCustomerPersonIdentity;
import com.positivity.securityservice.internal.entity.ExtPersonReplica;
import com.positivity.securityservice.internal.repository.ExtCustomerPersonIdentityRepository;
import com.positivity.securityservice.internal.repository.ExtPersonReplicaRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Assesses CRM conflict signals for self-registration from local replicas (ADR-0044 §6, #891) —
 * the replacement for the retired synchronous {@code CustomerRegistrationClient.searchPersons}.
 *
 * <p>Candidates are persons in the {@code ext_people_contact_person} identity replica matching
 * the registration's email, phone or full name (the same finders person resolution uses); each
 * candidate's customer standing (individual customer / commercial contact) comes from the
 * {@code ext_customer_person_identity} replica fed by pos-customer's person-identity facts. The
 * conflict rules downstream ({@code enforceCrmConflictRules}) consume the same
 * {@link CrmMatchSummaryDto} shape as before, so verdicts are unchanged.
 */
@Service
@RequiredArgsConstructor
public class CrmSignalService {

    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final ExtCustomerPersonIdentityRepository extCustomerPersonIdentityRepository;

    @Transactional(readOnly = true)
    public @NonNull CrmMatchSummaryDto assess(
            @NonNull String normalizedEmail,
            @Nullable String normalizedPhone,
            @NonNull String normalizedFirstName,
            @NonNull String normalizedLastName) {

        Map<UUID, ExtPersonReplica> candidates = new HashMap<>();
        Set<UUID> emailMatches = new LinkedHashSet<>();
        Set<UUID> phoneMatches = new LinkedHashSet<>();
        Set<UUID> nameMatches = new LinkedHashSet<>();

        String email = normalizedEmail.toLowerCase(Locale.ROOT);
        for (ExtPersonReplica person :
                extPersonReplicaRepository.findByPrimaryEmailIgnoreCaseOrSecondaryEmailIgnoreCase(email, email)) {
            candidates.put(person.getPersonId(), person);
            emailMatches.add(person.getPersonId());
        }
        if (normalizedPhone != null && !normalizedPhone.isBlank()) {
            for (ExtPersonReplica person :
                    extPersonReplicaRepository.findByPrimaryPhoneOrSecondaryPhone(normalizedPhone, normalizedPhone)) {
                candidates.put(person.getPersonId(), person);
                phoneMatches.add(person.getPersonId());
            }
        }
        for (ExtPersonReplica person : extPersonReplicaRepository.findByLastNameIgnoreCase(normalizedLastName)) {
            if (equalsIgnoreCase(person.getFirstName(), normalizedFirstName)) {
                candidates.put(person.getPersonId(), person);
                nameMatches.add(person.getPersonId());
            }
        }

        Map<UUID, ExtCustomerPersonIdentity> standings = new HashMap<>();
        if (!candidates.isEmpty()) {
            for (ExtCustomerPersonIdentity identity :
                    extCustomerPersonIdentityRepository.findAllById(candidates.keySet())) {
                standings.put(identity.getPersonId(), identity);
            }
        }

        List<UUID> candidateIds = List.copyOf(candidates.keySet());
        int individualCustomerCandidateCount = 0;
        int commercialContactCandidateCount = 0;
        int sharedIdentityCandidateCount = 0;
        for (UUID personId : candidateIds) {
            ExtCustomerPersonIdentity standing = standings.get(personId);
            boolean individual = standing != null && standing.isIndividualCustomer();
            boolean commercial = standing != null && standing.isCommercialContact();
            if (individual) {
                individualCustomerCandidateCount++;
            }
            if (commercial) {
                commercialContactCandidateCount++;
            }
            if (individual && commercial) {
                sharedIdentityCandidateCount++;
            }
        }

        boolean exactEmailMatch = !emailMatches.isEmpty();
        boolean exactPhoneMatch = !phoneMatches.isEmpty();
        boolean exactNameMatch = !nameMatches.isEmpty();
        boolean reviewRequired = !candidateIds.isEmpty()
                && (exactEmailMatch || (exactPhoneMatch && exactNameMatch) || sharedIdentityCandidateCount > 0);

        return CrmMatchSummaryDto.builder()
                .candidateCount(candidateIds.size())
                .anyMatches(!candidateIds.isEmpty())
                .individualCustomerCandidateCount(individualCustomerCandidateCount)
                .commercialContactCandidateCount(commercialContactCandidateCount)
                .sharedIdentityCandidateCount(sharedIdentityCandidateCount)
                .exactEmailMatch(exactEmailMatch)
                .exactPhoneMatch(exactPhoneMatch)
                .exactNameMatch(exactNameMatch)
                .reviewRequired(reviewRequired)
                .build();
    }

    private static boolean equalsIgnoreCase(@Nullable String value, @NonNull String expected) {
        return value != null && value.equalsIgnoreCase(expected);
    }
}
