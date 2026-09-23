package com.positivity.location.internal.service;

import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.location.internal.entity.ExtPersonReplica;
import com.positivity.location.internal.entity.ProcessedEvent;
import com.positivity.location.internal.repository.ExtPersonReplicaRepository;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.TransientDataAccessException;
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
 * (ADR-0044 §6, #885), giving {@code responsible_person_id} reads a local person identity source.
 *
 * <p>Phase 3.4 consumer contract: {@code processed_events} idempotency in the apply transaction,
 * strictly-below stale guard on the emission-timestamp {@code aggregateVersion}, transient DB
 * errors rethrown for container retry/DLQ. The topic also carries user-link facts this module
 * ignores — their eventIds are still recorded so the owner's manifest reconciles.
 *
 * <p>Transaction shape (#2146): the listener method is deliberately not {@code @Transactional}. The
 * handler and its {@code processed_events} mark commit together in their own {@code REQUIRES_NEW}
 * transaction, so a permanent failure rolls back only that work and is recorded in a separate
 * transaction; before, it poisoned the listener's shared transaction, whose commit then threw and
 * sent the record through the container's retry ladder to the DLQ. Transient failures still
 * propagate for container retry, with nothing recorded.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.location.kafka", name = "enabled", havingValue = "true")
public class PeopleContactEventsListener {

    static final String OWNER = "people-contact";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final Counter payloadRejectedCounter;

    /** Runs the handler with its processed mark in one transaction of their own; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public PeopleContactEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtPersonReplicaRepository extPersonReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extPersonReplicaRepository = extPersonReplicaRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "people-contact-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.location.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId = "${pos.location.kafka.people-contact-events-consumer-group:pos-location-people-contact-events}")
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
                        // owner's manifest counts every fact in the window.
                        log.debug("Ignoring people-contact event type={} eventId={}", eventType, eventId);
                }
                recordProcessed(eventId);
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed people-contact event payload eventId={}: {}", eventId, e.getMessage(), e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId));
        } catch (Exception e) {
            log.warn("Skipping malformed people-contact event eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId));
        }
    }

    /** The {@code processed_events} mark; callers supply the transaction. */
    private void recordProcessed(@NonNull String eventId) {
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
        String primaryEmail = primaryEmailOf(payload.contactPoints());
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

    /**
     * The value of the person's primary {@code EMAIL} contact point, or {@code null} when they
     * have none. {@link PersonUpdatedV1.ContactPointV1#value()} is itself non-null, so the
     * absence of a primary email is expressed here rather than by a null element.
     */
    @Nullable
    private static String primaryEmailOf(@NonNull List<PersonUpdatedV1.ContactPointV1> contactPoints) {
        for (PersonUpdatedV1.ContactPointV1 contactPoint : contactPoints) {
            if ("EMAIL".equals(contactPoint.contactType()) && contactPoint.primary()) {
                return contactPoint.value();
            }
        }
        return null;
    }

    private void applyPersonDeleted(JsonNode envelope) {
        PersonDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), PersonDeletedV1.class);
        extPersonReplicaRepository.deleteById(payload.personId());
    }
}
