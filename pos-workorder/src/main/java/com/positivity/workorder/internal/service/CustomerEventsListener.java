package com.positivity.workorder.internal.service;

import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.workorder.internal.entity.ExtCustomerPartyReplica;
import com.positivity.workorder.internal.entity.ProcessedEvent;
import com.positivity.workorder.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.workorder.internal.repository.ProcessedEventRepository;
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
 * Consumes {@code customer.events.v1} into the {@code ext_customer_party} replica (ADR-0044 §6,
 * #891) carrying the owner-computed requirements-met verdict for workorder gating. Idempotent via
 * {@code processed_events}; strictly-below stale guard on the emission-timestamp
 * aggregateVersion; transient errors rethrown for retry/DLQ. Ignored event types on the topic
 * still record their eventId so manifest reconciliation cannot read them as drift.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "workorder.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {

    static final String OWNER = "customer";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository;

    @KafkaListener(
            topics = "${workorder.kafka.customer-events-topic:customer.events.v1}",
            groupId = "${workorder.kafka.customer-events-consumer-group:pos-workorder-customer-events}")
    @Transactional
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
            switch (eventType == null ? "" : eventType) {
                case CustomerPartyUpdatedV1.EVENT_TYPE -> applyPartyUpdated(envelope);
                case CustomerPartyDeletedV1.EVENT_TYPE -> applyPartyDeleted(envelope);
                default -> log.debug("Ignoring customer event type={}", eventType);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed customer event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyPartyUpdated(JsonNode envelope) {
        CustomerPartyUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), CustomerPartyUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtCustomerPartyReplica existing =
                extCustomerPartyReplicaRepository.findById(payload.partyId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extCustomerPartyReplicaRepository.save(ExtCustomerPartyReplica.builder()
                .partyId(payload.partyId())
                .partyType(payload.partyType())
                .displayName(payload.displayName())
                .status(payload.status())
                .requirementsMet(payload.requirementsMet())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Updated ext_customer_party partyId={} requirementsMet={} version={}",
                payload.partyId(),
                payload.requirementsMet(),
                aggregateVersion);
    }

    private void applyPartyDeleted(JsonNode envelope) {
        CustomerPartyDeletedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), CustomerPartyDeletedV1.class);
        extCustomerPartyReplicaRepository.deleteById(payload.partyId());
        log.info("Deleted ext_customer_party partyId={}", payload.partyId());
    }
}
