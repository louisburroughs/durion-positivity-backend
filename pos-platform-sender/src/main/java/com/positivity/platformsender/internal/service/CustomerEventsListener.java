package com.positivity.platformsender.internal.service;

import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.platformsender.internal.entity.ExtCustomerPersonParty;
import com.positivity.platformsender.internal.entity.ProcessedEvent;
import com.positivity.platformsender.internal.repository.ExtCustomerPersonPartyRepository;
import com.positivity.platformsender.internal.repository.ProcessedEventRepository;
import com.positivity.tenancy.kafka.RetryableConsumerFailures;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code customer.events.v1} into the {@code ext_customer_person_party} replica (ADR-0044
 * R3): which pos-people-contact person each CRM person party is, the first hop of address
 * resolution. Only person parties are kept (a commercial party has no contact points of its own; its
 * messages go to the person party the caller names as {@code contactId}). Idempotent via
 * {@code processed_events}; a version strictly below the stored one is stale and skipped (the
 * owner's aggregateVersion is the party's JPA version); retryable failures rethrown for container
 * retry and {@code {topic}.dlq}. Every other event type on the topic is recorded and ignored.
 *
 * <p>Same transaction shape as {@link PeopleContactEventsListener} (ADR-0044 amendment 2026-09-23).
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.platform-sender.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {

    static final String OWNER = "customer";

    static final String PARTY_TYPE_PERSON = "PERSON";

    private static final String PAYLOAD = "payload";
    private static final String AGGREGATE_VERSION = "aggregateVersion";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCustomerPersonPartyRepository personPartyRepository;
    private final @Nullable Counter payloadRejectedCounter;

    /** The handler and its processed mark commit in one transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public CustomerEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCustomerPersonPartyRepository personPartyRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.personPartyRepository = personPartyRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description("Replica event payloads rejected due to Jackson databind failures")
                        .tag("owner", OWNER)
                        .tag("entity", "customer-events")
                        .register(registry);
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.platform-sender.kafka.customer-events-topic:customer.events.v1}",
            groupId = "${pos.platform-sender.kafka.customer-events-consumer-group:pos-platform-sender-customer-events}")
    public void onCustomerEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable customer event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping customer event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            handlerTransaction.executeWithoutResult(_ -> {
                switch (eventType == null ? "" : eventType) {
                    case CustomerPartyUpdatedV1.EVENT_TYPE -> applyPartyUpdated(envelope);
                    case CustomerPartyDeletedV1.EVENT_TYPE -> applyPartyDeleted(envelope);
                    default ->
                        // Recorded anyway: the owner's manifest counts every fact on the topic.
                        log.debug("Ignoring customer event type={}", eventType);
                }
                recordProcessed(eventId);
            });
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed customer event payload eventId={}: {}", eventId, e.getMessage(), e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId));
        } catch (Exception e) {
            if (RetryableConsumerFailures.isRetryable(e)) {
                // The container retries with backoff, then publishes to {topic}.dlq (ADR-0044 §4).
                throw e;
            }
            log.warn("Skipping malformed customer event eventId={}", eventId, e);
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

    private void applyPartyUpdated(JsonNode envelope) {
        CustomerPartyUpdatedV1 payload = objectMapper.treeToValue(envelope.path(PAYLOAD), CustomerPartyUpdatedV1.class);
        if (!PARTY_TYPE_PERSON.equals(payload.partyType()) || payload.personId() == null) {
            return;
        }
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
        if (isStale(payload.partyId(), aggregateVersion)) {
            return;
        }
        personPartyRepository.save(ExtCustomerPersonParty.builder()
                .partyId(payload.partyId())
                .personId(payload.personId())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.debug(
                "Updated ext_customer_person_party partyId={} personId={} version={}",
                payload.partyId(),
                payload.personId(),
                aggregateVersion);
    }

    /** A versioned tombstone (no person), so a replayed older update cannot resurrect the party. */
    private void applyPartyDeleted(JsonNode envelope) {
        CustomerPartyDeletedV1 payload = objectMapper.treeToValue(envelope.path(PAYLOAD), CustomerPartyDeletedV1.class);
        if (!personPartyRepository.existsById(payload.partyId()) && payload.personId() == null) {
            // Never a person party this replica knew of, and the fact names no person: nothing to mark.
            return;
        }
        long aggregateVersion = envelope.path(AGGREGATE_VERSION).longValue(0);
        if (isStale(payload.partyId(), aggregateVersion)) {
            return;
        }
        personPartyRepository.save(ExtCustomerPersonParty.builder()
                .partyId(payload.partyId())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.debug("Tombstoned ext_customer_person_party partyId={} version={}", payload.partyId(), aggregateVersion);
    }

    private boolean isStale(UUID partyId, long incomingVersion) {
        return personPartyRepository
                .findById(partyId)
                .map(existing -> existing.getAggregateVersion() > incomingVersion)
                .orElse(false);
    }
}
