package com.positivity.warranty.internal.service;

import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.warranty.internal.entity.ExtCatalogReplica;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ExtCatalogReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
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
 * Consumes {@code catalog.events.v1} into the {@code ext_catalog} replica (ADR-0044 §6, #924) — the
 * event-fed replacement for the retired synchronous {@code CatalogClient.getProduct}. Candidate-line
 * product resolution and warranty eligibility read manufacturer / warranty terms from this replica.
 *
 * <p>Consumer contract mirrors the module's other listeners: {@code processed_events} idempotency
 * (owner {@code catalog}) in the apply transaction, strictly-below stale guard on the fact's
 * {@code aggregateVersion} (pos-catalog stamps {@code updatedAt} epoch millis there, monotonic per
 * product), transient DB errors rethrown for container retry/DLQ. Unsupported event types still
 * record their eventIds so the owner's manifest reconciles.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.warranty.kafka", name = "enabled", havingValue = "true")
public class CatalogEventsListener {

    /** Producing domain, per the repo-wide processed_events convention (manifest scans key on it). */
    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCatalogReplicaRepository extCatalogReplicaRepository;

    @KafkaListener(
            topics = "${pos.warranty.kafka.catalog-events-topic:catalog.events.v1}",
            groupId = "${pos.warranty.kafka.catalog-events-consumer-group:pos-warranty-catalog-events}")
    @Transactional
    public void onCatalogEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable catalog event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping catalog event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            if (ProductUpdatedV1.EVENT_TYPE.equals(eventType)) {
                applyProductUpdated(envelope);
            } else {
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                log.debug("Ignoring catalog event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed catalog event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyProductUpdated(JsonNode envelope) {
        ProductUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), ProductUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtCatalogReplica existing =
                extCatalogReplicaRepository.findById(payload.productId()).orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extCatalogReplicaRepository.save(ExtCatalogReplica.builder()
                .productId(payload.productId())
                .sku(payload.sku())
                .name(payload.name())
                .manufacturerId(payload.manufacturerId())
                .manufacturerName(payload.manufacturerName())
                .manufacturerBrand(payload.manufacturerBrand())
                .categoryId(payload.categoryId())
                .category(payload.category())
                .warranty(payload.warranty())
                .manufacturerWarranty(payload.manufacturerWarranty())
                .active(payload.active())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info("Updated ext_catalog productId={} version={}", payload.productId(), aggregateVersion);
    }
}
