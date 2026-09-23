package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.peoplecontact.PersonDeletedV1;
import com.positivity.domainevents.peoplecontact.PersonUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtPersonReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.repository.ExtPersonReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
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
 * (ADR-0044 §6, #885), giving {@code technician.person_id} reads a local person identity source.
 *
 * <p>Phase 3.4 consumer contract: {@code processed_events} idempotency, strictly-below stale guard on the emission-timestamp {@code aggregateVersion}, transient DB
 * errors rethrown for container retry/DLQ. The topic also carries user-link facts this module
 * ignores — their eventIds are still recorded so the owner's manifest reconciles.
 *
 * <p><strong>Transaction shape (#2146).</strong> The listener method is not {@code @Transactional}:
 * the handler's work and the {@code processed_events} mark commit together in a
 * {@code REQUIRES_NEW} transaction of their own, so neither lands without the other. A permanent
 * failure rolls back only that work and is recorded in a separate transaction, instead of
 * poisoning a shared transaction whose commit then throws and sends the record through retry and
 * dead-lettering. Transient database errors still propagate, unrecorded, for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.shop-manager.kafka", name = "enabled", havingValue = "true")
public class PeopleContactEventsListener {

    static final String OWNER = "people-contact";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtPersonReplicaRepository extPersonReplicaRepository;
    private final Counter payloadRejectedCounter;

    /** Runs the handler with its processed mark, and records a failure; see the class doc. */
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
            topics = "${pos.shop-manager.kafka.people-contact-events-topic:people-contact.events.v1}",
            groupId =
                    "${pos.shop-manager.kafka.people-contact-events-consumer-group:pos-shop-manager-people-contact-events}")
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
                processedEventRepository.save(processedMark(eventId));
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed people-contact event payload eventId={}: {}", eventId, e.getMessage(), e);
            recordFailed(eventId);
        } catch (Exception e) {
            log.warn("Skipping malformed people-contact event eventId={}", eventId, e);
            recordFailed(eventId);
        }
    }

    private ProcessedEvent processedMark(String eventId) {
        return ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build();
    }

    /** Records a permanently failed event in a transaction of its own; see the class doc. */
    private void recordFailed(String eventId) {
        handlerTransaction.executeWithoutResult(_ -> processedEventRepository.save(processedMark(eventId)));
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
