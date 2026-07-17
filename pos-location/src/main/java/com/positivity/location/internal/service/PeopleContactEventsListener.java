package com.positivity.location.internal.service;

import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.location.internal.entity.ExtPersonReplica;
import com.positivity.location.internal.entity.ProcessedEvent;
import com.positivity.location.internal.repository.ExtPersonReplicaRepository;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code people-contact.events.v1} into the {@code ext_people_contact_person} replica
 * (ADR-0044 §6, #885), giving {@code responsible_person_id} reads a local person identity source.
 *
 * <p>Phase 3.4 consumer contract: {@code processed_events} idempotency in the apply transaction,
 * strictly-below stale guard on the emission-timestamp {@code aggregateVersion}, transient DB
 * errors rethrown for container retry/DLQ. The topic also carries user-link facts this module
 * ignores — their eventIds are still recorded so the owner's manifest reconciles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.location.kafka", name = "enabled", havingValue = "true")
public class PeopleContactEventsListener {

    static final String OWNER = "people-contact";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;

    @KafkaListener(
            topics = "${pos.location.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId = "${pos.location.kafka.people-contact-events-consumer-group:pos-location-people-contact-events}")
    @Transactional
    public void onPeopleContactEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable people-contact event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping people-contact event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case PersonUpdatedV1.EVENT_TYPE -> applyPersonUpdated(envelope);
                case PersonDeletedV1.EVENT_TYPE -> applyPersonDeleted(envelope);
                default ->
                    // Ignored types still fall through to the processed_events insert below: the
                    // owner's manifest counts every fact in the window.
                    log.debug("Ignoring people-contact event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed people-contact event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyPersonUpdated(JsonNode envelope) {
        PersonUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), PersonUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtPersonReplica existing =
                extPersonReplicaRepository.findById(payload.personId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        String primaryEmail = payload.contactPoints().stream()
                .filter(cp -> "EMAIL".equals(cp.contactType()) && cp.primary())
                .map(PersonUpdatedV1.ContactPointV1::value)
                .findFirst()
                .orElse(null);
        String contactJson;
        try {
            contactJson = objectMapper.writeValueAsString(payload.contactPoints());
        } catch (Exception e) {
            contactJson = null;
        }
        extPersonReplicaRepository.save(ExtPersonReplica.builder()
                .personId(payload.personId())
                .firstName(payload.firstName())
                .lastName(payload.lastName())
                .primaryEmail(primaryEmail)
                .contactPoints(contactJson)
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }

    private void applyPersonDeleted(JsonNode envelope) {
        PersonDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), PersonDeletedV1.class);
        extPersonReplicaRepository.deleteById(payload.personId());
    }
}
