package com.positivity.securityservice.internal.service;

import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemovedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkUpdatedV1;
import com.positivity.securityservice.internal.entity.ExtPersonReplica;
import com.positivity.securityservice.internal.entity.ProcessedEvent;
import com.positivity.securityservice.internal.entity.User;
import com.positivity.securityservice.internal.repository.ExtPersonReplicaRepository;
import com.positivity.securityservice.internal.repository.ProcessedEventRepository;
import com.positivity.securityservice.internal.repository.UserRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
 * Consumes {@code people-contact.events.v1} into the {@code users.person_id} projection and the
 * {@code ext_people_contact_person} replica (amended ADR-0043 §2, #876).
 *
 * <p>Link facts are the ONLY writer of {@code users.person_id}: an active link fact sets the
 * projection on the matching username, a removed fact clears it (guarded by the link's personId,
 * so a stale removal cannot clear a newer link). Person facts maintain the identity replica used
 * by local self-registration person resolution. Idempotent via {@code processed_events} in the
 * apply transaction; transient DB errors rethrow for container retry/DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.security-service.kafka", name = "enabled", havingValue = "true")
public class PeopleContactEventsListener {

    static final String OWNER = "people-contact";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final UserRepository userRepository;

    @KafkaListener(
            topics = "${pos.security-service.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId =
                    "${pos.security-service.kafka.people-contact-events-consumer-group:pos-security-people-contact-events}")
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
                case UserPersonLinkUpdatedV1.EVENT_TYPE -> applyLinkUpdated(envelope);
                case UserPersonLinkRemovedV1.EVENT_TYPE -> applyLinkRemoved(envelope);
                case PersonUpdatedV1.EVENT_TYPE -> applyPersonUpdated(envelope);
                case PersonDeletedV1.EVENT_TYPE -> applyPersonDeleted(envelope);
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

    private void applyLinkUpdated(JsonNode envelope) {
        UserPersonLinkUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), UserPersonLinkUpdatedV1.class);
        Optional<User> user = userRepository.findByUsername(payload.username());
        if (user.isEmpty()) {
            // Links can exist for people-module usernames that have no security account (or the
            // account is created moments later); reconciliation re-applies via replay.
            log.info("No security user for link fact username={} — projection unchanged", payload.username());
            return;
        }
        User target = user.get();
        if ("ACTIVE".equals(payload.status())) {
            target.setPersonId(payload.personId());
        } else if (payload.personId().equals(target.getPersonId())) {
            target.setPersonId(null);
        }
        userRepository.save(target);
        log.info(
                "users.person_id projection updated username={} personId={} status={}",
                payload.username(),
                payload.personId(),
                payload.status());
    }

    private void applyLinkRemoved(JsonNode envelope) {
        UserPersonLinkRemovedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), UserPersonLinkRemovedV1.class);
        userRepository.findByUsername(payload.username()).ifPresent(user -> {
            // Guard on the removed link's personId so a stale removal (redelivered after a new
            // link was applied) cannot clear the newer projection.
            if (payload.personId().equals(user.getPersonId())) {
                user.setPersonId(null);
                userRepository.save(user);
                log.info("users.person_id projection cleared username={}", payload.username());
            }
        });
    }

    private void applyPersonUpdated(JsonNode envelope) {
        PersonUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), PersonUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtPersonReplica existing =
                extPersonReplicaRepository.findById(payload.personId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
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
    }

    private void applyPersonDeleted(JsonNode envelope) {
        PersonDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), PersonDeletedV1.class);
        extPersonReplicaRepository.deleteById(payload.personId());
    }

    private @Nullable String email(List<PersonUpdatedV1.ContactPointV1> contacts, boolean primary) {
        return contacts.stream()
                .filter(cp -> "EMAIL".equals(cp.contactType()) && cp.primary() == primary)
                .map(PersonUpdatedV1.ContactPointV1::value)
                .findFirst()
                .orElse(null);
    }

    private @Nullable String workPhone(List<PersonUpdatedV1.ContactPointV1> contacts, int index) {
        List<String> phones = contacts.stream()
                .filter(cp -> "PHONE_WORK".equals(cp.contactType()))
                .map(PersonUpdatedV1.ContactPointV1::value)
                .toList();
        return index < phones.size() ? phones.get(index) : null;
    }
}
