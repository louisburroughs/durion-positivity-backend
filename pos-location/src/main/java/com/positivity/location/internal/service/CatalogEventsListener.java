package com.positivity.location.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.catalog.CatalogServiceUpdatedV1;
import com.positivity.location.internal.entity.ExtCatalogServiceReplica;
import com.positivity.location.internal.entity.ProcessedEvent;
import com.positivity.location.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.location.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
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
 * Consumes {@code catalog.events.v1} into the {@code ext_catalog_service} replica (ADR-0044 §6),
 * so a bay's specialty claim can be validated against the catalog operation vocabulary without a
 * synchronous cross-module read.
 *
 * <p>Only {@code catalog.service.updated} is applied. The same topic carries product facts that
 * this module has no use for; unlike {@code InventoryEventsListener} there is no catalog manifest
 * listener here, so facts of other types are skipped without recording a dedup row — nothing
 * compares counts against them.
 *
 * <p>Consumer contract, matching the module's other listeners: {@code processed_events}
 * idempotency in the apply transaction, transient DB errors rethrown for container retry/DLQ,
 * unparsable or malformed payloads logged and dropped rather than poisoning the partition.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.location.kafka", name = "enabled", havingValue = "true")
public class CatalogEventsListener {

    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCatalogServiceReplicaRepository extCatalogServiceReplicaRepository;

    public CatalogEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCatalogServiceReplicaRepository extCatalogServiceReplicaRepository) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extCatalogServiceReplicaRepository = extCatalogServiceReplicaRepository;
    }

    @KafkaListener(
            topics = "${pos.location.kafka.catalog-events-topic:catalog.events.v1}",
            groupId = "${pos.location.kafka.catalog-events-consumer-group:pos-location-catalog-events}")
    @Transactional
    public void onCatalogEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable catalog event", e);
            return;
        }
        if (!CatalogServiceUpdatedV1.EVENT_TYPE.equals(
                envelope.path("eventType").stringValue(null))) {
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping catalog event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            applyCatalogService(envelope);
            processedEventRepository.save(ProcessedEvent.builder()
                    .eventId(eventId)
                    .owner(OWNER)
                    .processedAt(Instant.now(clock))
                    .build());
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed catalog event eventId={}", eventId, e);
        }
    }

    /**
     * Upserts the replica row from a {@code catalog.service.updated} fact.
     *
     * <p>The stale guard compares the fact's {@code aggregateVersion} against the version already
     * held, via {@link ReplicaVersionGuard}. It applies on equality rather than skipping, matching
     * the module's other replicas: pos-catalog's counter strictly advances, so an equal version is
     * identical content, and {@code POST .../facts/replay} deliberately resends at the held
     * version to repair a replica holding the right version but wrong rows — skipping on equal
     * would turn replay into a no-op.
     *
     * <p>A delete tombstone lands as {@code active = false} rather than removing the row, so a
     * retired operation code is distinguishable from one that was never in the catalog. That
     * distinction is what lets a rejected specialty claim say which of the two it was.
     */
    private void applyCatalogService(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID serviceId = UUID.fromString(payload.path("serviceId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        Long held = extCatalogServiceReplicaRepository
                .findById(serviceId)
                .map(ExtCatalogServiceReplica::getAggregateVersion)
                .orElse(null);
        if (held != null && ReplicaVersionGuard.isStale(held, aggregateVersion)) {
            return;
        }

        extCatalogServiceReplicaRepository.save(ExtCatalogServiceReplica.builder()
                .serviceId(serviceId)
                .name(payload.path("name").stringValue(null))
                .operationCode(payload.path("operationCode").stringValue(null))
                .active(payload.path("active").booleanValue(false))
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
    }
}
