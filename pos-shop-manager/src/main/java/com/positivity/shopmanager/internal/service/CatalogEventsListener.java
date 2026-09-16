package com.positivity.shopmanager.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.catalog.CatalogServiceUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceReplica;
import com.positivity.shopmanager.internal.entity.ExtCatalogServiceSkillReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtCatalogServiceSkillReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 * Consumes {@code catalog.events.v1} for the one fact this module schedules against: the catalog
 * service and its skill requirement ({@code catalog.service.updated}, schema v3; CAP-329), mirrored
 * into {@code ext_catalog_service} and {@code ext_catalog_service_skill} so a booking resolves
 * (service, vehicle class) → required skills from replicas alone (ADR-0044 §6). Product facts on
 * the topic are acknowledged and ignored.
 *
 * <p>Same shape as {@link PeopleEventsListener}: idempotent on eventId through {@code
 * processed_events}, stale snapshots dropped by aggregate version, malformed payloads counted and
 * skipped rather than retried. The skill children are a replace-set per fact — the owner's
 * declaration is the whole truth, not a delta.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.shop-manager.kafka", name = "enabled", havingValue = "true")
public class CatalogEventsListener {

    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCatalogServiceReplicaRepository serviceReplicaRepository;
    private final ExtCatalogServiceSkillReplicaRepository skillReplicaRepository;
    private final Counter payloadRejectedCounter;

    public CatalogEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCatalogServiceReplicaRepository serviceReplicaRepository,
            ExtCatalogServiceSkillReplicaRepository skillReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.serviceReplicaRepository = serviceReplicaRepository;
        this.skillReplicaRepository = skillReplicaRepository;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. omitted primitive fields)")
                        .tag("owner", OWNER)
                        .tag("entity", "catalog-events")
                        .register(registry);
    }

    @KafkaListener(
            topics = "${pos.shop-manager.kafka.catalog-events-topic:catalog.events.v1}",
            groupId = "${pos.shop-manager.kafka.catalog-events-consumer-group:pos-shop-manager-catalog-events}")
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
            switch (eventType == null ? "" : eventType) {
                case CatalogServiceUpdatedV1.EVENT_TYPE -> applyServiceUpdated(envelope, eventId);
                default -> log.debug("Ignoring catalog event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed catalog event payload eventId={}: {}", eventId, e.getMessage(), e);
        } catch (Exception e) {
            log.warn("Skipping malformed catalog event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    /**
     * Upsert the service row and replace its skill children. A pre-v3 fact carries no requirement
     * fields and is stored as "not configured" with no children — which is exactly what it says.
     */
    private void applyServiceUpdated(@NonNull JsonNode envelope, @NonNull String eventId) throws DatabindException {
        CatalogServiceUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), CatalogServiceUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtCatalogServiceReplica existing =
                serviceReplicaRepository.findById(payload.serviceId()).orElse(null);
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            log.debug(
                    "Ignoring stale catalog service event serviceId={} version={} eventId={}",
                    payload.serviceId(),
                    aggregateVersion,
                    eventId);
            return;
        }
        serviceReplicaRepository.save(ExtCatalogServiceReplica.builder()
                .serviceId(payload.serviceId())
                .name(payload.name())
                .operationCode(payload.operationCode())
                .active(payload.active())
                .requirementsConfiguredAt(payload.requirementsConfiguredAt())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        skillReplicaRepository.deleteAllByServiceId(payload.serviceId());
        List<CatalogServiceUpdatedV1.RequiredSkill> required =
                payload.requiredSkills() == null ? List.of() : payload.requiredSkills();
        skillReplicaRepository.saveAll(required.stream()
                .map(skill -> ExtCatalogServiceSkillReplica.builder()
                        .serviceId(payload.serviceId())
                        .skillId(skill.skillId())
                        .skillCode(skill.skillCode())
                        .minGvwrClass(skill.minGvwrClass())
                        .maxGvwrClass(skill.maxGvwrClass())
                        .build())
                .toList());
    }
}
