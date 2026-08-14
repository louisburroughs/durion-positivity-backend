package com.positivity.location.internal.service;

import com.positivity.domainevents.inventory.StorageLocationOnHandUpdatedV1;
import com.positivity.location.internal.entity.ExtStorageLocationOnHandReplica;
import com.positivity.location.internal.entity.ProcessedEvent;
import com.positivity.location.internal.repository.ExtStorageLocationOnHandReplicaRepository;
import com.positivity.location.internal.repository.ProcessedEventRepository;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code inventory.events.v1} into the {@code ext_storage_location_on_hand} replica
 * (ADR-0044 §6, #899), replacing the retired synchronous {@code LocationInventoryInquiryClient}
 * guard.
 *
 * <p>Phase 3.4 consumer contract: {@code processed_events} idempotency in the apply transaction,
 * strictly-below stale guard on the emission-timestamp {@code aggregateVersion}, transient DB
 * errors rethrown for container retry/DLQ. The topic also carries availability and lead-time
 * facts this module ignores — their eventIds are still recorded so the owner's manifest
 * reconciles.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.location.kafka", name = "enabled", havingValue = "true")
public class InventoryEventsListener {

    static final String OWNER = "inventory";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtStorageLocationOnHandReplicaRepository extStorageLocationOnHandReplicaRepository;
    private final Counter payloadRejectedCounter;

    public InventoryEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtStorageLocationOnHandReplicaRepository extStorageLocationOnHandReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extStorageLocationOnHandReplicaRepository = extStorageLocationOnHandReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", "inventory")
                        .tag("entity", "inventory-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.location.kafka.inventory-events-topic:inventory.events.v1}",
            groupId = "${pos.location.kafka.inventory-events-consumer-group:pos-location-inventory-events}")
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
            if (StorageLocationOnHandUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyOnHandUpdated(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring inventory event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed inventory event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed inventory event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyOnHandUpdated(JsonNode envelope) {
        StorageLocationOnHandUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), StorageLocationOnHandUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtStorageLocationOnHandReplica existing = extStorageLocationOnHandReplicaRepository
                .findById(payload.storageLocationId())
                .orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extStorageLocationOnHandReplicaRepository.save(ExtStorageLocationOnHandReplica.builder()
                .storageLocationId(payload.storageLocationId())
                .onHandQuantity(payload.onHandQuantity())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Updated ext_storage_location_on_hand storageLocationId={} onHand={} version={}",
                payload.storageLocationId(),
                payload.onHandQuantity(),
                aggregateVersion);
    }
}
