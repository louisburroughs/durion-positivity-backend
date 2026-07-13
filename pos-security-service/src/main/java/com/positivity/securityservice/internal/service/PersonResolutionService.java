package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.securityservice.internal.entity.ExtPersonReplica;
import com.positivity.shared.id.UUIDv7Generator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Local person resolution for self-registration (amended ADR-0043, #876).
 *
 * <p>Replaces the retired synchronous {@code POST /v1/people/resolve} call: matching runs against
 * the {@code ext_people_contact_person} replica with the authority's weights (EMAIL 60, PHONE 25,
 * LAST_NAME 10, FIRST_NAME 5, threshold 30), and a miss creates the person by generating the id
 * here and queueing a {@code people-contact.person.upsert-requested} command — eventually
 * consistent, so a person registered moments earlier may not match yet; the linked-user rules
 * (which use the local {@code users.person_id} projection) still block duplicate active accounts.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonResolutionService {

    private static final int EMAIL_WEIGHT = 60;
    private static final int PHONE_WEIGHT = 25;
    private static final int LAST_NAME_WEIGHT = 10;
    private static final int FIRST_NAME_WEIGHT = 5;
    private static final int MATCH_THRESHOLD = 30;

    private final com.positivity.securityservice.internal.repository.ExtPersonReplicaRepository replicaRepository;
    private final PeopleContactCommandEmitter commandEmitter;

    /** Weighted match against the identity replica; empty when no candidate reaches threshold. */
    @NonNull
    public Optional<UUID> match(
            @NonNull String email, @Nullable String phone, @NonNull String firstName, @NonNull String lastName) {
        return bestMatch(email, phone, firstName, lastName);
    }

    /**
     * Create the person by generating the id here and queueing the upsert command. Must be called
     * inside the caller's transaction so the command commits atomically with the registration
     * outcome.
     */
    @NonNull
    public UUID createPerson(
            @NonNull String email, @Nullable String phone, @NonNull String firstName, @NonNull String lastName) {
        UUID personId = UUIDv7Generator.generate();
        commandEmitter.requestPersonUpsert(new PersonUpsertRequestedV1(
                personId, firstName, lastName, null, email, null, phone == null ? List.of() : List.of(phone)));
        log.info("No person match — queued person upsert personId={}", personId);
        return personId;
    }

    private Optional<UUID> bestMatch(String email, @Nullable String phone, String firstName, String lastName) {
        Map<UUID, Integer> scores = new HashMap<>();

        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        for (ExtPersonReplica person : replicaRepository.findByPrimaryEmailIgnoreCaseOrSecondaryEmailIgnoreCase(
                normalizedEmail, normalizedEmail)) {
            scores.merge(person.getPersonId(), EMAIL_WEIGHT, Integer::sum);
        }
        if (phone != null && !phone.isBlank()) {
            for (ExtPersonReplica person : replicaRepository.findByPrimaryPhoneOrSecondaryPhone(phone, phone)) {
                scores.merge(person.getPersonId(), PHONE_WEIGHT, Integer::sum);
            }
        }
        for (ExtPersonReplica person : replicaRepository.findByLastNameIgnoreCase(lastName)) {
            scores.merge(person.getPersonId(), LAST_NAME_WEIGHT, Integer::sum);
        }
        for (ExtPersonReplica person : replicaRepository.findByFirstNameIgnoreCase(firstName)) {
            scores.merge(person.getPersonId(), FIRST_NAME_WEIGHT, Integer::sum);
        }

        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() >= MATCH_THRESHOLD)
                .max(Map.Entry.<UUID, Integer>comparingByValue()
                        .thenComparing(entry -> entry.getKey().toString()))
                .map(Map.Entry::getKey);
    }
}
