package com.positivity.platformsender.internal.service;

import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.kafka.common.KafkaRails;
import com.positivity.platformsender.internal.entity.ExtPeopleContactPerson;
import com.positivity.platformsender.internal.entity.ProcessedEvent;
import com.positivity.platformsender.internal.repository.ExtPeopleContactPersonRepository;
import com.positivity.platformsender.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code people-contact.events.v1} into the {@code ext_people_contact_person} replica
 * (ADR-0044 R3): each person's email and mobile phone, the two contact points this module delivers
 * to. Idempotent via {@code processed_events}; stale versions skipped (strictly-below guard, the
 * producer's aggregateVersion being an emission-timestamp LWW hint); retryable failures rethrown for
 * container retry and {@code {topic}.dlq}. Other event types on the topic are recorded and ignored.
 *
 * <p><b>Transaction shape (ADR-0044 amendment 2026-09-23).</b> The listener method is not
 * {@code @Transactional}: the handler and its {@code processed_events} mark commit together in
 * their own {@code REQUIRES_NEW} transaction. A permanent failure rolls back only that work and is
 * then recorded in a separate transaction; a retryable one propagates before anything is written.
 */
@Slf4j
@Component
@KafkaRails
public class PeopleContactEventsListener {

    static final String OWNER = "people-contact";

    static final String CONTACT_TYPE_EMAIL = "EMAIL";
    static final String CONTACT_TYPE_MOBILE = "PHONE_MOBILE";

    private static final String PAYLOAD = "payload";
    private static final String AGGREGATE_VERSION = "aggregateVersion";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPeopleContactPersonRepository personRepository;
    private final @Nullable Counter payloadRejectedCounter;

    /** The handler and its processed mark commit in one transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public PeopleContactEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtPeopleContactPersonRepository personRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.personRepository = personRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description("Replica event payloads rejected due to Jackson databind failures")
                        .tag("owner", OWNER)
                        .tag("entity", "people-contact-events")
                        .register(registry);
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.platform-sender.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId =
                    "${pos.platform-sender.kafka.people-contact-events-consumer-group:pos-platform-sender-people-contact-events}")
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
            handlerTransaction.executeWithoutResult(_ -> {
                switch (eventType == null ? "" : eventType) {
                    case PersonUpdatedV1.EVENT_TYPE -> applyPersonUpdated(envelope);
                    case PersonDeletedV1.EVENT_TYPE -> applyPersonDeleted(envelope);
                    default ->
                        // Ignored types still fall through to the processed_events insert below: the
                        // owner's manifest counts every fact in the window, so skipping the insert
                        // would register as replica drift and trigger a pointless replay.
                        log.debug("Ignoring people-contact event type={}", eventType);
                }
                recordProcessed(eventId);
            });
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed people-contact event payload eventId={}: {}", eventId, e.getMessage(), e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId));
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // The container retries with backoff, then publishes to {topic}.dlq (ADR-0044 §4).
                throw e;
            }
            log.warn("Skipping malformed people-contact event eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId));
        }
    }

    private void recordProcessed(@NonNull String eventId) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyPersonUpdated(JsonNode envelope) {
        PersonUpdatedV1 payload = objectMapper.treeToValue(envelope.path(PAYLOAD), PersonUpdatedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
        if (isStale(payload.personId(), aggregateVersion)) {
            return;
        }
        List<PersonUpdatedV1.ContactPointV1> points =
                payload.contactPoints() == null ? List.of() : payload.contactPoints();
        personRepository.save(ExtPeopleContactPerson.builder()
                .personId(payload.personId())
                .email(preferred(points, CONTACT_TYPE_EMAIL))
                .mobilePhone(preferred(points, CONTACT_TYPE_MOBILE))
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.debug("Updated ext_people_contact_person personId={} version={}", payload.personId(), aggregateVersion);
    }

    /**
     * A versioned tombstone rather than a delete: a delete would drop the version watermark and let
     * a replayed older update resurrect the addresses of a person who no longer exists.
     */
    private void applyPersonDeleted(JsonNode envelope) {
        PersonDeletedV1 payload = objectMapper.treeToValue(envelope.path(PAYLOAD), PersonDeletedV1.class);
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
        if (isStale(payload.personId(), aggregateVersion)) {
            return;
        }
        personRepository.save(ExtPeopleContactPerson.builder()
                .personId(payload.personId())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.debug("Tombstoned ext_people_contact_person personId={} version={}", payload.personId(), aggregateVersion);
    }

    private boolean isStale(UUID personId, long incomingVersion) {
        return personRepository
                .findById(personId)
                .map(existing -> existing.getAggregateVersion() > incomingVersion)
                .orElse(false);
    }

    /** The primary contact point of the type, else the first one; {@code null} when there is none. */
    static @Nullable String preferred(List<PersonUpdatedV1.ContactPointV1> points, String contactType) {
        return points.stream()
                .filter(point -> contactType.equals(point.contactType()))
                .filter(point -> point.value() != null && !point.value().isBlank())
                .min(Comparator.comparing(point -> !point.primary()))
                .map(PersonUpdatedV1.ContactPointV1::value)
                .orElse(null);
    }
}
