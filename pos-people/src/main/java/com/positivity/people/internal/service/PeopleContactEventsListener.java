package com.positivity.people.internal.service;

import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemovedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkUpdatedV1;
import com.positivity.people.internal.entity.ExtPersonReplica;
import com.positivity.people.internal.entity.ExtUserLinkReplica;
import com.positivity.people.internal.entity.ProcessedEvent;
import com.positivity.people.internal.repository.ExtPersonReplicaRepository;
import com.positivity.people.internal.repository.ExtUserLinkReplicaRepository;
import com.positivity.people.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code people-contact.events.v1} into the {@code ext_people_contact_person} and
 * {@code ext_people_contact_user_link} replicas (ADR-0044 §6, #875).
 *
 * <p>Same contract as the vehicle replica listener in pos-customer: idempotent via
 * {@code processed_events} in the upsert transaction, transient DB errors rethrown for container
 * retry/DLQ, malformed payloads logged and skipped. The producer's {@code aggregateVersion} is an
 * emission-timestamp LWW hint (not a strict JPA version), so the stale guard skips only versions
 * strictly below the replica's — equal versions re-apply, which is harmless because the payload
 * is a full snapshot.
 *
 * <p>Deletions remove the replica row outright; a stale post-delete update can transiently
 * resurrect it until the next reconciliation window repairs the drift — accepted, deletes are
 * rare and reconciliation is mandatory (ADR-0044 §4).
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.people.kafka", name = "enabled", havingValue = "true")
public class PeopleContactEventsListener {

    static final String OWNER = "people-contact";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final ExtUserLinkReplicaRepository extUserLinkReplicaRepository;

    @KafkaListener(
            topics = "${pos.people.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId = "${pos.people.kafka.people-contact-events-consumer-group:pos-people-people-contact-events}")
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
            log.debug("Skipping duplicate people-contact event eventId={}", eventId);
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case PersonUpdatedV1.EVENT_TYPE -> applyPersonUpdated(envelope);
                case PersonDeletedV1.EVENT_TYPE -> applyPersonDeleted(envelope);
                case UserPersonLinkUpdatedV1.EVENT_TYPE -> applyLinkUpdated(envelope);
                case UserPersonLinkRemovedV1.EVENT_TYPE -> applyLinkRemoved(envelope);
                default -> {
                    log.debug("Ignoring people-contact event type={}", eventType);
                    return;
                }
            }
        } catch (TransientDataAccessException e) {
            // Retry with backoff / DLQ via the container error handler (ADR-0044 §4).
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
            log.debug(
                    "Skipping stale person event personId={} eventVersion={} replicaVersion={}",
                    payload.personId(),
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }

        List<PersonUpdatedV1.ContactPointV1> contacts = payload.contactPoints();
        extPersonReplicaRepository.save(ExtPersonReplica.builder()
                .personId(payload.personId())
                .firstName(payload.firstName())
                .lastName(payload.lastName())
                .preferredName(payload.preferredName())
                .primaryEmail(email(contacts, true))
                .secondaryEmail(email(contacts, false))
                .primaryPhone(workPhone(contacts, 0))
                .secondaryPhone(workPhone(contacts, 1))
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

    private void applyLinkUpdated(JsonNode envelope) {
        UserPersonLinkUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), UserPersonLinkUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);

        ExtUserLinkReplica existing =
                extUserLinkReplicaRepository.findById(payload.linkId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extUserLinkReplicaRepository.save(ExtUserLinkReplica.builder()
                .linkId(payload.linkId())
                .personId(payload.personId())
                .username(payload.username())
                .status(payload.status())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_people_contact_user_link linkId={} username={}", payload.linkId(), payload.username());
    }

    private void applyLinkRemoved(JsonNode envelope) {
        UserPersonLinkRemovedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), UserPersonLinkRemovedV1.class);
        extUserLinkReplicaRepository.deleteById(payload.linkId());
        log.info("Deleted ext_people_contact_user_link linkId={}", payload.linkId());
    }

    /** Primary (isPrimary) or first non-primary EMAIL contact point. */
    private @Nullable String email(List<PersonUpdatedV1.ContactPointV1> contacts, boolean primary) {
        return contacts.stream()
                .filter(cp -> "EMAIL".equals(cp.contactType()) && cp.primary() == primary)
                .map(PersonUpdatedV1.ContactPointV1::value)
                .findFirst()
                .orElse(null);
    }

    /** N-th PHONE_WORK contact point in payload order. */
    private @Nullable String workPhone(List<PersonUpdatedV1.ContactPointV1> contacts, int index) {
        List<String> phones = contacts.stream()
                .filter(cp -> "PHONE_WORK".equals(cp.contactType()))
                .map(PersonUpdatedV1.ContactPointV1::value)
                .toList();
        return index < phones.size() ? phones.get(index) : null;
    }
}
