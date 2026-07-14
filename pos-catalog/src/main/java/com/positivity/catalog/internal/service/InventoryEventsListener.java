package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.entity.ExtInventoryAvailabilityReplica;
import com.positivity.catalog.internal.entity.ExtProductLeadTimeReplica;
import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.repository.ExtInventoryAvailabilityReplicaRepository;
import com.positivity.catalog.internal.repository.ExtProductLeadTimeReplicaRepository;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.inventory.InventoryAvailabilityUpdatedV1;
import com.positivity.domainevents.inventory.LeadTimeUpdatedV1;
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
 * Consumes {@code inventory.events.v1} into the {@code ext_inventory_availability} and
 * {@code ext_product_lead_time} replicas (ADR-0044 §6, #899), replacing the retired synchronous
 * {@code InventoryClientImpl} lookups behind product-detail display.
 *
 * <p>Phase 3.4 consumer contract: {@code processed_events} idempotency in the apply transaction,
 * strictly-below stale guard on the emission-timestamp {@code aggregateVersion}, transient DB
 * errors rethrown for container retry/DLQ. Storage-location on-hand facts on the topic are
 * ignored, but their eventIds are still recorded so the owner's manifest reconciles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.catalog.kafka", name = "enabled", havingValue = "true")
public class InventoryEventsListener {

    static final String OWNER = "inventory";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtInventoryAvailabilityReplicaRepository extInventoryAvailabilityReplicaRepository;
    private final ExtProductLeadTimeReplicaRepository extProductLeadTimeReplicaRepository;

    @KafkaListener(
            topics = "${pos.catalog.kafka.inventory-events-topic:inventory.events.v1}",
            groupId = "${pos.catalog.kafka.inventory-events-consumer-group:pos-catalog-inventory-events}")
    @Transactional
    public void onInventoryEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable inventory event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping inventory event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case InventoryAvailabilityUpdatedV1.EVENT_TYPE -> applyAvailabilityUpdated(envelope);
                case LeadTimeUpdatedV1.EVENT_TYPE -> applyLeadTimeUpdated(envelope);
                // Ignored types (e.g. storage-location on-hand facts) still fall through to the
                // processed_events insert below: the owner's manifest counts every fact.
                default -> log.debug("Ignoring inventory event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed inventory event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyAvailabilityUpdated(JsonNode envelope) {
        InventoryAvailabilityUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), InventoryAvailabilityUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        UUID aggregateId = UUID.fromString(envelope.path("aggregateId").stringValue(null));
        ExtInventoryAvailabilityReplica existing =
                extInventoryAvailabilityReplicaRepository.findById(aggregateId).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extInventoryAvailabilityReplicaRepository.save(ExtInventoryAvailabilityReplica.builder()
                .aggregateId(aggregateId)
                .stockItemId(payload.stockItemId())
                .locationId(payload.locationId())
                .onHandQuantity(payload.onHandQuantity())
                .allocatedQuantity(payload.allocatedQuantity())
                .availableToPromiseQuantity(payload.availableToPromiseQuantity())
                .unitOfMeasure(payload.unitOfMeasure())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Updated ext_inventory_availability sku={} locationId={} version={}",
                payload.stockItemId(),
                payload.locationId(),
                aggregateVersion);
    }

    private void applyLeadTimeUpdated(JsonNode envelope) {
        LeadTimeUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), LeadTimeUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtProductLeadTimeReplica existing =
                extProductLeadTimeReplicaRepository.findById(payload.productId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extProductLeadTimeReplicaRepository.save(ExtProductLeadTimeReplica.builder()
                .productId(payload.productId())
                .minDays(payload.minDays())
                .maxDays(payload.maxDays())
                .displayText(payload.displayText())
                .source(payload.source())
                .confidence(payload.confidence())
                .asOf(payload.asOf())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_product_lead_time productId={} version={}", payload.productId(), aggregateVersion);
    }
}
