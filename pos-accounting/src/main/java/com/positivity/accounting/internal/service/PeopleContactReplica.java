package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtPeopleContactPerson;
import com.positivity.accounting.internal.entity.ExtPeopleContactUserLink;
import com.positivity.accounting.internal.entity.ProcessedEvent;
import com.positivity.accounting.internal.repository.ExtPeopleContactPersonRepository;
import com.positivity.accounting.internal.repository.ExtPeopleContactUserLinkRepository;
import com.positivity.accounting.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemovedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkUpdatedV1;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Writes accounting's people-contact copy (AP reads #2670; ADR-0044 §4, §6) for {@link PeopleContactEventsListener},
 * and reads its ledger for {@link PeopleContactManifestListener}: the listeners hold no repository.
 *
 * <p>Each fact applies by its aggregate id and the envelope's {@code aggregateVersion} ({@link ReplicaVersionGuard}):
 * an older fact changes nothing, an equal one applies, so a manifest-driven replay repairs a row. A deleted person and
 * a removed link are deleted from the copy, so the next read serves a null name. Every eventId on the topic is recorded
 * in {@code processed_events} with owner {@value #OWNER}, the types this copy ignores included, because the owner's
 * manifest counts every fact.
 *
 * <p>Names are CONFIDENTIAL (ADR-0072): nothing here logs a payload, a name or a row; only ids, types and versions.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PeopleContactReplica {

    /** The {@code processed_events} owner tag of this copy, the manifest's ledger scope. */
    static final String OWNER = "people-contact";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEvents;
    private final ExtPeopleContactPersonRepository persons;
    private final ExtPeopleContactUserLinkRepository links;

    /** Whether {@code eventId} was applied (or recorded) already: a redelivery writes nothing. */
    @Transactional(readOnly = true)
    public boolean isProcessed(@NonNull String eventId) {
        return processedEvents.existsById(eventId);
    }

    /**
     * Applies one fact and records its eventId, together, in a transaction of their own (#2146): a permanent failure
     * rolls back only this work and the listener records the eventId on its own.
     *
     * @param eventId   the envelope's eventId
     * @param eventType the envelope's eventType; a type this copy does not keep is only recorded
     * @param envelope  the whole envelope (its {@code aggregateVersion} and {@code payload})
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void apply(@NonNull String eventId, @Nullable String eventType, @NonNull JsonNode envelope) {
        switch (eventType == null ? "" : eventType) {
            case PersonUpdatedV1.EVENT_TYPE -> personUpdated(envelope);
            case PersonDeletedV1.EVENT_TYPE -> personDeleted(envelope);
            case UserPersonLinkUpdatedV1.EVENT_TYPE -> linkUpdated(envelope);
            case UserPersonLinkRemovedV1.EVENT_TYPE -> linkRemoved(envelope);
            default ->
                log.debug("Recording people-contact event type={} eventId={} without applying it", eventType, eventId);
        }
        record(eventId);
    }

    /** Records {@code eventId} as processed in a transaction of its own: the permanent-failure path. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(@NonNull String eventId) {
        record(eventId);
    }

    /** The eventIds this copy recorded for {@code tenantId} in a manifest window, bounds as UUIDv7 strings. */
    @Transactional(readOnly = true)
    public @NonNull List<String> receivedEventIds(
            @NonNull UUID tenantId, @NonNull String lowerBound, @NonNull String upperBound) {
        return processedEvents.findEventIdsInRangeForOwner(OWNER, tenantId, lowerBound, upperBound);
    }

    private void record(String eventId) {
        processedEvents.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private static long aggregateVersion(JsonNode envelope) {
        return envelope.path("aggregateVersion").longValue(0);
    }

    private void personUpdated(JsonNode envelope) {
        PersonUpdatedV1 fact = objectMapper.treeToValue(envelope.path("payload"), PersonUpdatedV1.class);
        long version = aggregateVersion(envelope);
        ExtPeopleContactPerson existing = persons.findById(fact.personId()).orElse(null);
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), version)) {
            log.debug(
                    "Ignoring stale people-contact.person.updated person={} held={} incoming={}",
                    fact.personId(),
                    existing.getAggregateVersion(),
                    version);
            return;
        }
        ExtPeopleContactPerson copy = existing != null ? existing : new ExtPeopleContactPerson();
        copy.setPersonId(fact.personId());
        copy.setFirstName(fact.firstName());
        copy.setLastName(fact.lastName());
        copy.setAggregateVersion(version);
        copy.setUpdatedAt(Instant.now(clock));
        persons.save(copy);
    }

    private void personDeleted(JsonNode envelope) {
        PersonDeletedV1 fact = objectMapper.treeToValue(envelope.path("payload"), PersonDeletedV1.class);
        persons.findById(fact.personId()).ifPresent(persons::delete);
    }

    private void linkUpdated(JsonNode envelope) {
        UserPersonLinkUpdatedV1 fact =
                objectMapper.treeToValue(envelope.path("payload"), UserPersonLinkUpdatedV1.class);
        long version = aggregateVersion(envelope);
        ExtPeopleContactUserLink existing = links.findById(fact.linkId()).orElse(null);
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), version)) {
            log.debug(
                    "Ignoring stale people-contact.user-person-link.updated link={} held={} incoming={}",
                    fact.linkId(),
                    existing.getAggregateVersion(),
                    version);
            return;
        }
        ExtPeopleContactUserLink copy = existing != null ? existing : new ExtPeopleContactUserLink();
        copy.setLinkId(fact.linkId());
        copy.setPersonId(fact.personId());
        copy.setUsername(fact.username());
        copy.setStatus(fact.status());
        copy.setAggregateVersion(version);
        copy.setUpdatedAt(Instant.now(clock));
        links.save(copy);
    }

    private void linkRemoved(JsonNode envelope) {
        UserPersonLinkRemovedV1 fact =
                objectMapper.treeToValue(envelope.path("payload"), UserPersonLinkRemovedV1.class);
        links.findById(fact.linkId()).ifPresent(links::delete);
    }
}
