package com.positivity.invoice.internal.service;

import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.invoice.internal.entity.ExtCustomerPartyReplica;
import com.positivity.invoice.internal.entity.ProcessedEvent;
import com.positivity.invoice.internal.repository.ExtCustomerPartyReplicaRepository;
import com.positivity.invoice.internal.repository.ProcessedEventRepository;
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
 * #891). Only party updated/deleted facts are handled — person-identity and billing-rules facts
 * on the same topic are other consumers' concerns. Idempotent via {@code processed_events};
 * strictly-below stale guard on the emission-timestamp aggregateVersion; transient errors
 * rethrown for retry/DLQ.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.invoice.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {

    static final String OWNER = "customer";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCustomerPartyReplicaRepository extCustomerPartyReplicaRepository;

    @KafkaListener(
            topics = "${pos.invoice.kafka.customer-events-topic:customer.events.v1}",
            groupId = "${pos.invoice.kafka.customer-events-consumer-group:pos-invoice-customer-events}")
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
                // Ignored types still fall through to the processed_events insert below:
                // the owner's manifest counts every fact in the window, so skipping the
                // record would read as permanent drift and trigger useless replays.
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
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_customer_party partyId={} version={}", payload.partyId(), aggregateVersion);
    }

    private void applyPartyDeleted(JsonNode envelope) {
        CustomerPartyDeletedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), CustomerPartyDeletedV1.class);
        extCustomerPartyReplicaRepository.deleteById(payload.partyId());
        log.info("Deleted ext_customer_party partyId={}", payload.partyId());
    }
}
