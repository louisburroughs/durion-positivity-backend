package com.positivity.accounting.internal.service;

import com.positivity.kafka.common.KafkaRails;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes pos-people-contact's {@code people-contact.events.v1} into accounting's minimal people-contact copy (AP
 * reads #2670; ADR-0044 §6, the pos-location and pos-customer precedent): {@code person.updated} / {@code
 * .person.deleted} into {@code ext_people_contact_person} and {@code user-person-link.updated} / {@code .removed} into
 * {@code ext_people_contact_user_link}. The copy gives the AP reads a display name beside each actor's username without
 * a call to another service.
 *
 * <p>The listener holds no repository: {@link PeopleContactReplica} applies each fact with its {@code
 * processed_events} mark in a {@code REQUIRES_NEW} transaction of its own (#2146), by version, and records the types it
 * ignores too. A permanent failure is logged by id and type and marked processed; a retryable one propagates for
 * container retry and dead-lettering (ADR-0044 §4).
 *
 * <p>First fill: the group reads from the earliest offset, so persons and links published before it first ran are
 * applied once; the owner's manifest repairs any window still missing.
 *
 * <p>Names are CONFIDENTIAL (ADR-0072): a message, a payload or a name is never logged.
 */
@Slf4j
@Component
@KafkaRails
public class PeopleContactEventsListener {

    private final ObjectMapper objectMapper;
    private final PeopleContactReplica replica;

    public PeopleContactEventsListener(ObjectMapper objectMapper, PeopleContactReplica replica) {
        this.objectMapper = objectMapper;
        this.replica = replica;
    }

    @KafkaListener(
            topics = "${pos.accounting.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId =
                    "${pos.accounting.kafka.people-contact-events-consumer-group:pos-accounting-people-contact-events}",
            properties = "auto.offset.reset=earliest")
    public void onPeopleContactEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            // The message is never logged: it may carry a person's name.
            log.warn("Skipping unparsable people-contact event ({} characters)", message.length());
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping people-contact event without eventId");
            return;
        }
        if (replica.isProcessed(eventId)) {
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        try {
            replica.apply(eventId, eventType, envelope);
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // The container retries with backoff, then publishes to {topic}.dlq (ADR-0044 §4).
                throw e;
            }
            log.warn(
                    "Skipping malformed people-contact event eventId={} eventType={}: {}",
                    eventId,
                    eventType,
                    e.getClass().getSimpleName());
            replica.markProcessed(eventId);
        }
    }
}
