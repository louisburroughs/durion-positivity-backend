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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
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
public class CatalogEventsListener {

    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCatalogServiceReplicaRepository serviceReplicaRepository;
    private final ExtCatalogServiceSkillReplicaRepository skillReplicaRepository;
    private final Counter payloadRejectedCounter;

    /** Runs the handler with its processed mark, and records a failure; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public CatalogEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCatalogServiceReplicaRepository serviceReplicaRepository,
            ExtCatalogServiceSkillReplicaRepository skillReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.serviceReplicaRepository = serviceReplicaRepository;
        this.skillReplicaRepository = skillReplicaRepository;
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
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
            handlerTransaction.executeWithoutResult(_ -> {
                switch (eventType == null ? "" : eventType) {
                    case CatalogServiceUpdatedV1.EVENT_TYPE -> applyServiceUpdated(envelope, eventId);
                    default -> log.debug("Ignoring catalog event type={} eventId={}", eventType, eventId);
                }
                processedEventRepository.save(processedMark(eventId));
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed catalog event payload eventId={}: {}", eventId, e.getMessage(), e);
            recordFailed(eventId);
        } catch (Exception e) {
            log.warn("Skipping malformed catalog event eventId={}", eventId, e);
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
