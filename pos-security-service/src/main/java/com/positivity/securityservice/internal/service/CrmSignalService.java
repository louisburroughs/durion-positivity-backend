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

        Candidates candidates =
                gatherCandidates(normalizedEmail, normalizedPhone, normalizedFirstName, normalizedLastName);
        List<UUID> candidateIds = List.copyOf(candidates.byPersonId().keySet());
        StandingTally standings = tallyStandings(candidateIds);

        boolean exactEmailMatch = !candidates.emailMatches().isEmpty();
        boolean exactPhoneMatch = !candidates.phoneMatches().isEmpty();
        boolean exactNameMatch = !candidates.nameMatches().isEmpty();

        // An email hit alone is enough; phone and name are only conclusive together; and a person
        // who is both an individual customer and a commercial contact always warrants a look.
        boolean reviewRequired = !candidateIds.isEmpty()
                && (exactEmailMatch || (exactPhoneMatch && exactNameMatch) || standings.shared() > 0);

        return CrmMatchSummaryDto.builder()
                .candidateCount(candidateIds.size())
                .anyMatches(!candidateIds.isEmpty())
                .individualCustomerCandidateCount(standings.individual())
                .commercialContactCandidateCount(standings.commercial())
                .sharedIdentityCandidateCount(standings.shared())
                .exactEmailMatch(exactEmailMatch)
                .exactPhoneMatch(exactPhoneMatch)
                .exactNameMatch(exactNameMatch)
                .reviewRequired(reviewRequired)
                .build();
    }

    /**
     * Everyone the three signals turned up, and which signal turned each of them up.
     *
     * <p>One person can appear under several signals; {@code byPersonId} deduplicates them while the
     * three sets keep which signals fired, because the verdict depends on the combination.
     */
    private record Candidates(
            Map<UUID, ExtPersonReplica> byPersonId,
            Set<UUID> emailMatches,
            Set<UUID> phoneMatches,
            Set<UUID> nameMatches) {}

    /** How many candidates hold each customer standing. */
    private record StandingTally(int individual, int commercial, int shared) {}

    private Candidates gatherCandidates(
            @NonNull String normalizedEmail,
            @Nullable String normalizedPhone,
            @NonNull String normalizedFirstName,
            @NonNull String normalizedLastName) {
        Map<UUID, ExtPersonReplica> byPersonId = new HashMap<>();
        Set<UUID> emailMatches = new LinkedHashSet<>();
        Set<UUID> phoneMatches = new LinkedHashSet<>();
        Set<UUID> nameMatches = new LinkedHashSet<>();

        String email = normalizedEmail.toLowerCase(Locale.ROOT);
        for (ExtPersonReplica person :
                extPersonReplicaRepository.findByPrimaryEmailIgnoreCaseOrSecondaryEmailIgnoreCase(email, email)) {
            byPersonId.put(person.getPersonId(), person);
            emailMatches.add(person.getPersonId());
        }
        if (normalizedPhone != null && !normalizedPhone.isBlank()) {
            for (ExtPersonReplica person :
                    extPersonReplicaRepository.findByPrimaryPhoneOrSecondaryPhone(normalizedPhone, normalizedPhone)) {
                byPersonId.put(person.getPersonId(), person);
                phoneMatches.add(person.getPersonId());
            }
        }
        // Surname is the indexed query; the given name is filtered in memory, so a shared surname
        // does not become a match on its own.
        for (ExtPersonReplica person : extPersonReplicaRepository.findByLastNameIgnoreCase(normalizedLastName)) {
            if (equalsIgnoreCase(person.getFirstName(), normalizedFirstName)) {
                byPersonId.put(person.getPersonId(), person);
                nameMatches.add(person.getPersonId());
            }
        }
        return new Candidates(byPersonId, emailMatches, phoneMatches, nameMatches);
    }

    private StandingTally tallyStandings(@NonNull List<UUID> candidateIds) {
        if (candidateIds.isEmpty()) {
            return new StandingTally(0, 0, 0);
        }
        Map<UUID, ExtCustomerPersonIdentity> standings = new HashMap<>();
        for (ExtCustomerPersonIdentity identity : extCustomerPersonIdentityRepository.findAllById(candidateIds)) {
            standings.put(identity.getPersonId(), identity);
        }

        int individual = 0;
        int commercial = 0;
        int shared = 0;
        for (UUID personId : candidateIds) {
            ExtCustomerPersonIdentity standing = standings.get(personId);
            boolean isIndividual = standing != null && standing.isIndividualCustomer();
            boolean isCommercial = standing != null && standing.isCommercialContact();
            if (isIndividual) {
                individual++;
            }
            if (isCommercial) {
                commercial++;
            }
            if (isIndividual && isCommercial) {
                shared++;
            }
        }
        return new StandingTally(individual, commercial, shared);
    }

    private static boolean equalsIgnoreCase(@Nullable String value, @NonNull String expected) {
        return value != null && value.equalsIgnoreCase(expected);
    }
}
