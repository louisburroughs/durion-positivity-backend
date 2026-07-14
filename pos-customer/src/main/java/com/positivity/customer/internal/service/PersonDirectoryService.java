package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.ExtPersonReplica;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.PersonUpsertRequestedV1;
import com.positivity.shared.id.UUIDv7Generator;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
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
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Local person directory backed by the {@code ext_people_contact_person} replica
 * (ADR-0044 §6, #877). Replaces the retired synchronous {@code PeopleClient}: reads come from
 * the replica, and identity writes leave as {@code people-contact.person.upsert-requested}
 * commands through the outbox (the confirming fact catches the replica up).
 *
 * <p>Person resolution mirrors the authority's weighted matching (EMAIL 60 / PHONE 25 /
 * LAST_NAME 10 / FIRST_NAME 5, threshold 30). Replica-based matching is eventually consistent:
 * a person created moments ago may not match yet — the same window the old sync resolve had
 * for concurrent writers, now slightly wider.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersonDirectoryService {

    private static final int EMAIL_WEIGHT = 60;
    private static final int PHONE_WEIGHT = 25;
    private static final int LAST_NAME_WEIGHT = 10;
    private static final int FIRST_NAME_WEIGHT = 5;
    private static final int MATCH_THRESHOLD = 30;

    private static final TypeReference<List<PersonUpdatedV1.ContactPointV1>> CONTACT_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final ExtPersonReplicaRepository replicaRepository;
    private final PeopleContactCommandEmitter commandEmitter;

    /** Match an existing person against the replica or create one via upsert command. */
    @NonNull
    public UUID resolveOrCreatePersonId(
            @Nullable String email, @Nullable String phone, @Nullable String lastName, @Nullable String firstName) {
        Optional<UUID> match = bestMatch(email, phone, firstName, lastName);
        if (match.isPresent()) {
            return match.get();
        }
        UUID personId = UUIDv7Generator.generate();
        commandEmitter.requestPersonUpsert(new PersonUpsertRequestedV1(
                personId,
                firstName,
                lastName,
                null,
                email,
                null,
                phone == null || phone.isBlank() ? List.of() : List.of(phone)));
        log.info("No person match — queued person upsert personId={}", personId);
        return personId;
    }

    /**
     * Replace a person's typed contact points via the full-typed upsert command. Names travel
     * from the replica so the full-attribute upsert cannot blank them; a not-yet-replicated
     * person keeps null names until its own fact lands (the upsert is keyed by personId either
     * way).
     */
    public void setContactPoints(@NonNull UUID personId, @NonNull List<ContactPointUpsert> contactPoints) {
        ExtPersonReplica existing = replicaRepository.findById(personId).orElse(null);
        List<PersonUpdatedV1.ContactPointV1> typed = contactPoints.stream()
                .map(cp -> new PersonUpdatedV1.ContactPointV1(cp.contactType(), cp.value(), cp.primary()))
                .toList();
        commandEmitter.requestPersonUpsert(new PersonUpsertRequestedV1(
                personId,
                existing != null ? existing.getFirstName() : null,
                existing != null ? existing.getLastName() : null,
                existing != null ? existing.getPreferredName() : null,
                null,
                null,
                List.of(),
                typed));
    }

    /** Batch identity lookup from the replica; unknown ids are absent from the map. */
    @NonNull
    public Map<UUID, PersonIdentity> fetchPersonIdentities(@NonNull Collection<UUID> personIds) {
        if (personIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, PersonIdentity> result = new LinkedHashMap<>();
        for (ExtPersonReplica person : replicaRepository.findByPersonIdIn(personIds)) {
            result.put(person.getPersonId(), toIdentity(person));
        }
        return result;
    }

    /**
     * Same as {@link #fetchPersonIdentities}; kept for call-site compatibility with the retired
     * client's resilient variant — a local replica read has no remote failure mode to swallow.
     */
    @NonNull
    public Map<UUID, PersonIdentity> fetchPersonIdentitiesQuietly(@NonNull Collection<UUID> personIds) {
        return fetchPersonIdentities(personIds);
    }

    /** Directory search over the replica (names + primary email). */
    @NonNull
    public List<PersonIdentity> searchPersons(@Nullable String q) {
        if (q == null || q.isBlank()) {
            return List.of();
        }
        return replicaRepository.search(q.trim()).stream().map(this::toIdentity).toList();
    }

    private Optional<UUID> bestMatch(
            @Nullable String email, @Nullable String phone, @Nullable String firstName, @Nullable String lastName) {
        Map<UUID, Integer> scores = new HashMap<>();
        if (email != null && !email.isBlank()) {
            for (ExtPersonReplica person :
                    replicaRepository.findByPrimaryEmailIgnoreCase(email.trim().toLowerCase(Locale.ROOT))) {
                scores.merge(person.getPersonId(), EMAIL_WEIGHT, Integer::sum);
            }
        }
        if (phone != null && !phone.isBlank()) {
            // DB narrows by JSON containment; re-verify the typed match here.
            String normalizedPhone = phone.trim();
            for (ExtPersonReplica person : replicaRepository.findByContactPointsContaining(normalizedPhone)) {
                if (contactPointsOf(person).stream()
                        .anyMatch(cp -> cp.contactType() != null
                                && cp.contactType().startsWith("PHONE")
                                && normalizedPhone.equals(cp.value()))) {
                    scores.merge(person.getPersonId(), PHONE_WEIGHT, Integer::sum);
                }
            }
        }
        if (lastName != null && !lastName.isBlank()) {
            for (ExtPersonReplica person : replicaRepository.findByLastNameIgnoreCase(lastName.trim())) {
                scores.merge(person.getPersonId(), LAST_NAME_WEIGHT, Integer::sum);
            }
        }
        if (firstName != null && !firstName.isBlank()) {
            for (ExtPersonReplica person : replicaRepository.findByFirstNameIgnoreCase(firstName.trim())) {
                scores.merge(person.getPersonId(), FIRST_NAME_WEIGHT, Integer::sum);
            }
        }
        return scores.entrySet().stream()
                .filter(entry -> entry.getValue() >= MATCH_THRESHOLD)
                .max(Map.Entry.<UUID, Integer>comparingByValue()
                        .thenComparing(entry -> entry.getKey().toString()))
                .map(Map.Entry::getKey);
    }

    private PersonIdentity toIdentity(ExtPersonReplica person) {
        List<ContactPoint> points = contactPointsOf(person).stream()
                .map(cp -> new ContactPoint(cp.contactType(), cp.value(), cp.primary()))
                .toList();
        return new PersonIdentity(
                person.getPersonId(), person.getFirstName(), person.getLastName(), person.getPrimaryEmail(), points);
    }

    private List<PersonUpdatedV1.ContactPointV1> contactPointsOf(ExtPersonReplica person) {
        if (person.getContactPoints() == null || person.getContactPoints().isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(person.getContactPoints(), CONTACT_LIST);
        } catch (Exception e) {
            log.warn("Unparsable contact_points JSON for personId={}", person.getPersonId(), e);
            return List.of();
        }
    }

    /** Contact point to upsert toward pos-people-contact. */
    public record ContactPointUpsert(String contactType, String value, boolean primary) {}

    /** Canonical person identity sourced from the replica. */
    public record PersonIdentity(
            UUID id, String firstName, String lastName, String primaryEmail, List<ContactPoint> contactPoints) {
        public String displayName() {
            return ((firstName != null ? firstName : "") + " " + (lastName != null ? lastName : "")).trim();
        }

        @NonNull
        public List<ContactPoint> contactPoints() {
            return contactPoints != null ? contactPoints : List.of();
        }

        /** Email values from typed contact points (falls back to primaryEmail). */
        @NonNull
        public List<String> emails() {
            List<String> fromPoints = contactPoints().stream()
                    .filter(cp -> "EMAIL".equals(cp.contactType()))
                    .map(ContactPoint::value)
                    .filter(v -> v != null && !v.isBlank())
                    .toList();
            if (!fromPoints.isEmpty()) {
                return fromPoints;
            }
            return primaryEmail != null && !primaryEmail.isBlank() ? List.of(primaryEmail) : List.of();
        }

        /** Phone values from typed contact points (any PHONE_* type). */
        @NonNull
        public List<String> phones() {
            return contactPoints().stream()
                    .filter(cp -> cp.contactType() != null && cp.contactType().startsWith("PHONE"))
                    .map(ContactPoint::value)
                    .filter(v -> v != null && !v.isBlank())
                    .toList();
        }
    }

    /** Typed contact point sourced from the replica. */
    public record ContactPoint(String contactType, String value, boolean isPrimary) {}
}
