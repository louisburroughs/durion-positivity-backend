package com.positivity.customer.internal.service;

import com.positivity.customer.internal.entity.ExtPersonReplica;
import com.positivity.customer.internal.entity.ProcessedEvent;
import com.positivity.customer.internal.repository.ExtPersonReplicaRepository;
import com.positivity.customer.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
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
 * (ADR-0044 §6, #877). Same contract as the vehicle replica listener: idempotent via
 * {@code processed_events}, stale versions skipped (strictly-below guard — the producer's
 * aggregateVersion is an emission-timestamp LWW hint), transient errors rethrown for retry/DLQ.
 * Link facts are ignored here (pos-security-service owns that projection).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.customer.kafka", name = "enabled", havingValue = "true")
public class PeopleContactEventsListener {

    static final String OWNER = "people-contact";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;

    @KafkaListener(
            topics = "${pos.customer.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId = "${pos.customer.kafka.people-contact-events-consumer-group:pos-customer-people-contact-events}")
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
                default -> {
                    log.debug("Ignoring people-contact event type={}", eventType);
                    return;
                }
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
                .preferredName(payload.preferredName())
                .primaryEmail(primaryEmail)
                .contactPoints(contactJson)
                .personCreatedAt(payload.createdAt())
                .personUpdatedAt(payload.updatedAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_people_contact_person personId={} version={}", payload.personId(), aggregateVersion);
    }

    private void applyPersonDeleted(JsonNode envelope) {
        PersonDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), PersonDeletedV1.class);
        extPersonReplicaRepository.deleteById(payload.personId());
        log.info("Deleted ext_people_contact_person personId={}", payload.personId());
    }
}
