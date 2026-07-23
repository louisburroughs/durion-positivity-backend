package com.positivity.order.internal.service;

import com.positivity.domainevents.customer.CustomerPartyDeletedV1;
import com.positivity.domainevents.customer.CustomerPartyUpdatedV1;
import com.positivity.order.internal.entity.ExtCustomer;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtCustomerRepository;
import com.positivity.order.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
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
 * Consumes {@code customer.events.v1} into the {@code ext_customer} replica (ADR-0044 §6, parity
 * story I2 replica redesign). Same contract as the pos-customer/pos-invoice replica listeners:
 * idempotent via {@code processed_events} in the upsert transaction, stale envelopes
 * (aggregateVersion at or below the replica's) skipped, transient DB errors rethrown for container
 * retry, malformed payloads logged and skipped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class CustomerEventsListener {

    static final String OWNER = "customer";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCustomerRepository extCustomerRepository;

    @KafkaListener(
            topics = "${pos.order.kafka.customer-events-topic:customer.events.v1}",
            groupId = "${pos.order.kafka.customer-events-consumer-group:pos-order-customer-events}")
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
        boolean update = CustomerPartyUpdatedV1.EVENT_TYPE.equals(eventType);
        boolean delete = CustomerPartyDeletedV1.EVENT_TYPE.equals(eventType);
        if (!update && !delete) {
            log.debug("Ignoring customer event type={}", eventType);
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping customer event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            log.debug("Skipping duplicate customer event eventId={}", eventId);
            return;
        }

        try {
            if (update) {
                applyUpdate(envelope);
            } else {
                applyDelete(envelope);
            }
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .owner(OWNER)
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed customer event eventId={}", eventId, e);
        }
    }

    private void applyUpdate(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID partyId = UUID.fromString(payload.path("partyId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        ExtCustomer existing = extCustomerRepository.findById(partyId).orElse(null);
        if (existing != null && existing.getAggregateVersion() >= aggregateVersion) {
            log.debug(
                    "Skipping stale customer event for {} (v{} <= v{})",
                    partyId,
                    aggregateVersion,
                    existing.getAggregateVersion());
            return;
        }
        ExtCustomer replica = existing != null ? existing : new ExtCustomer();
        replica.setPartyId(partyId);
        replica.setStatus(payload.path("status").stringValue("UNKNOWN"));
        replica.setDisplayName(payload.path("displayName").stringValue(null));
        replica.setAggregateVersion(aggregateVersion);
        replica.setSyncedAt(Instant.now(clock));
        extCustomerRepository.save(replica);
    }

    private void applyDelete(JsonNode envelope) {
        UUID partyId = UUID.fromString(envelope.path("payload").path("partyId").stringValue(null));
        extCustomerRepository.deleteById(partyId);
    }
}
