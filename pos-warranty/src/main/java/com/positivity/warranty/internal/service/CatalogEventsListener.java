package com.positivity.warranty.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.catalog.ProductUpdatedV1;
import com.positivity.warranty.internal.entity.ExtCatalogReplica;
import com.positivity.warranty.internal.entity.ProcessedEvent;
import com.positivity.warranty.internal.repository.ExtCatalogReplicaRepository;
import com.positivity.warranty.internal.repository.ProcessedEventRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumes {@code catalog.events.v1} into the {@code ext_catalog} replica (ADR-0044 §6, #924) — the
 * event-fed replacement for the retired synchronous {@code CatalogClient.getProduct}. Candidate-line
 * product resolution and warranty eligibility read manufacturer / warranty terms from this replica.
 *
 * <p>Consumer contract mirrors the module's other listeners: {@code processed_events} idempotency
 * (owner {@code catalog}) in the apply transaction, a strictly-below stale guard on the fact's
 * {@code aggregateVersion} (pos-catalog's JPA {@code @Version}-backed counter, strictly advancing —
 * seeded from the legacy {@code updatedAt} epoch millis so magnitudes continue seamlessly),
 * transient DB errors rethrown for container retry/DLQ. Unsupported event types still record their
 * eventIds so the owner's manifest reconciles.
 *
 * <p>The guard is {@link ReplicaVersionGuard} (#1486): equal versions apply rather than skip, both
 * as an idempotent no-op for live traffic and because {@code POST .../facts/replay} depends on
 * equal-applies to repair a replica that holds the version number but wrong or missing rows.
 *
 * <p>Transaction shape (#2146): the listener method is not {@code @Transactional}. The handler
 * and its {@code processed_events} mark commit together in a {@code REQUIRES_NEW} transaction of
 * their own, so a permanent failure rolls back only that work instead of leaving a shared
 * transaction rollback-only (whose commit threw, making the container retry and dead-letter the
 * record), and the failure is then recorded in a separate transaction. Transient failures still
 * propagate unrecorded for container retry; there is no at-least-once window between handler and
 * mark.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.warranty.kafka", name = "enabled", havingValue = "true")
public class CatalogEventsListener {

    /** Producing domain, per the repo-wide processed_events convention (manifest scans key on it). */
    static final String OWNER = "catalog";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtCatalogReplicaRepository extCatalogReplicaRepository;
    private final Counter payloadRejectedCounter;

    /** The handler and its processed mark share one transaction; see the class doc. */
    private final TransactionTemplate handlerTransaction;

    public CatalogEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtCatalogReplicaRepository extCatalogReplicaRepository,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extCatalogReplicaRepository = extCatalogReplicaRepository;
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
            topics = "${pos.warranty.kafka.catalog-events-topic:catalog.events.v1}",
            groupId = "${pos.warranty.kafka.catalog-events-consumer-group:pos-warranty-catalog-events}")
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
                if (ProductUpdatedV1.EVENT_TYPE.equals(eventType)) {
                    applyProductUpdated(envelope);
                } else {
                    // Ignored types still fall through to the processed_events insert below: the
                    // owner's manifest counts every fact in the window.
                    log.debug("Ignoring catalog event type={} eventId={}", eventType, eventId);
                }
                markProcessed(eventId);
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed catalog event payload eventId={}: {}", eventId, e.getMessage(), e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        } catch (Exception e) {
            log.warn("Skipping malformed catalog event eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> markProcessed(eventId));
        }
    }

    private void markProcessed(@NonNull String eventId) {
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
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — catalog's
        // aggregateVersion strictly advances, so equal means identical content, and replay resends
        // the held version deliberately to repair a replica with wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
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
