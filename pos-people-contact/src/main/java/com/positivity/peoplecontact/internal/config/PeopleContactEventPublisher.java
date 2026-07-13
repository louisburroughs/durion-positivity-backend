package com.positivity.peoplecontact.internal.config;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkRemovedV1;
import com.positivity.domainevents.peoplecontact.UserPersonLinkUpdatedV1;
import com.positivity.peoplecontact.internal.entity.Person;
import com.positivity.peoplecontact.internal.entity.PersonContactPoint;
import com.positivity.peoplecontact.internal.entity.UserPersonLink;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Emits people-contact identity facts to the outbox after mutations (ADR-0044 §6, #874).
 *
 * <p>No-op when the Kafka feature flag ({@code pos.people-contact.kafka.enabled}) is off — the
 * {@link OutboxEventWriter} bean is conditional, so this publisher degrades gracefully. Must be
 * called inside the mutating transaction (the writer requires {@code MANDATORY} propagation).
 *
 * <p>Person and link rows carry no optimistic-lock version, so the envelope's
 * {@code aggregateVersion} is the emission timestamp in epoch milliseconds — a last-writer-wins
 * ordering hint per aggregate, monotonic under the single-writer transaction model.
 */
@Slf4j
@Component
public class PeopleContactEventPublisher {

    private final Clock clock;
    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final String eventsTopic;

    public PeopleContactEventPublisher(
            Clock clock,
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            // Same property ManifestPublisher scans event_outbox by, so an override can never
            // desync the outbox rows from the manifest computation.
            @Value("${pos.people-contact.kafka.events-topic:people-contact.events.v1}") String eventsTopic) {
        this.clock = clock;
        this.outboxEventWriter = outboxEventWriter;
        this.eventsTopic = eventsTopic;
    }

    public void publishPersonUpdated(@NonNull Person person, @NonNull List<PersonContactPoint> contactPoints) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        PersonUpdatedV1 payload = new PersonUpdatedV1(
                person.getId(),
                person.getFirstName(),
                person.getLastName(),
                person.getPreferredName(),
                contactPoints.stream()
                        .map(cp -> new PersonUpdatedV1.ContactPointV1(
                                cp.getContactType().name(), cp.getValue(), cp.isPrimary()))
                        .toList(),
                person.getCreatedAt(),
                person.getUpdatedAt());
        writer.publish(
                eventsTopic,
                envelope(PersonUpdatedV1.EVENT_TYPE, PersonUpdatedV1.SCHEMA_VERSION, person.getId(), payload));
        log.debug("Queued people-contact.person.updated personId={}", person.getId());
    }

    public void publishPersonDeleted(@NonNull UUID personId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        writer.publish(
                eventsTopic,
                envelope(
                        PersonDeletedV1.EVENT_TYPE,
                        PersonDeletedV1.SCHEMA_VERSION,
                        personId,
                        new PersonDeletedV1(personId)));
        log.debug("Queued people-contact.person.deleted personId={}", personId);
    }

    public void publishLinkUpdated(@NonNull UserPersonLink link) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        UserPersonLinkUpdatedV1 payload = new UserPersonLinkUpdatedV1(
                link.getId(),
                link.getPersonId(),
                link.getUsername(),
                link.getStatus() == null ? "ACTIVE" : link.getStatus().name(),
                link.getLinkType(),
                link.getCreatedAt(),
                link.getUpdatedAt());
        writer.publish(
                eventsTopic,
                envelope(
                        UserPersonLinkUpdatedV1.EVENT_TYPE,
                        UserPersonLinkUpdatedV1.SCHEMA_VERSION,
                        link.getId(),
                        payload));
        log.debug(
                "Queued people-contact.user-person-link.updated linkId={} username={}",
                link.getId(),
                link.getUsername());
    }

    public void publishLinkRemoved(@NonNull UserPersonLink link) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        writer.publish(
                eventsTopic,
                envelope(
                        UserPersonLinkRemovedV1.EVENT_TYPE,
                        UserPersonLinkRemovedV1.SCHEMA_VERSION,
                        link.getId(),
                        new UserPersonLinkRemovedV1(link.getId(), link.getPersonId(), link.getUsername())));
        log.debug(
                "Queued people-contact.user-person-link.removed linkId={} username={}",
                link.getId(),
                link.getUsername());
    }

    private <T> DomainEventEnvelope<T> envelope(String eventType, int schemaVersion, UUID aggregateId, T payload) {
        return DomainEventEnvelope.of(
                eventType,
                schemaVersion,
                aggregateId,
                Instant.now(clock).toEpochMilli(),
                "pos-people-contact",
                null,
                null,
                payload,
                clock);
    }
}
