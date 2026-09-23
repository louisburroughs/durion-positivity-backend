package com.positivity.catalog.internal.service;

import com.positivity.catalog.internal.entity.ExtLocationParentReplica;
import com.positivity.catalog.internal.entity.ExtLocationReplica;
import com.positivity.catalog.internal.entity.ProcessedEvent;
import com.positivity.catalog.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.catalog.internal.repository.ExtLocationReplicaRepository;
import com.positivity.catalog.internal.repository.ProcessedEventRepository;
import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
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
 * Consumes {@code location.events.v1} into this module's {@code ext_location} and
 * {@code ext_location_parent} replicas (ADR-0044 §6, #892).
 *
 * <p>This module replicates locations for one purpose: the materialised location-scope ancestor
 * sets ADR-0061 §2 evaluates in-process, so that {@link com.positivity.catalog.internal.controller.ProductController#createLocationPriceOverride} can be enforced without a
 * per-request call to pos-location (#1885). Each location fact replaces the child's full typed
 * parent-edge set and then asks {@link LocationHierarchyService#recomputeAncestors} to rebuild the
 * sets for the location and every replicated descendant — so a re-parented node propagates and a
 * parent arriving after its children pushes its ancestry down to them. Ingestion never fails closed
 * on a parent the replica has not seen yet; the scope check does.
 *
 * <p>Consumer contract mirrors this module's other listeners: {@code processed_events} idempotency
 * (owner {@code location}), a {@link ReplicaVersionGuard} stale guard on
 * the fact's {@code aggregateVersion}, and transient DB errors rethrown for container retry/DLQ.
 * Unsupported event types on the topic — storage-location, bay and mobile-unit facts — still record
 * their eventIds, because the owner's manifest counts every fact in the window and skipping the
 * record would read as permanent drift.
 *
 * <p><b>Transaction shape (#2146).</b> The listener method is not {@code @Transactional}: the
 * apply and its {@code processed_events} mark commit together in their own {@code REQUIRES_NEW}
 * transaction, so a permanent failure inside {@link LocationHierarchyService} or a repository rolls
 * back only that work — and is recorded in a separate transaction — instead of poisoning a shared
 * transaction whose commit the container would retry to the DLQ. There is no window between an
 * applied fact and its mark. Transient failures still propagate for container retry.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "pos.catalog.kafka", name = "enabled", havingValue = "true")
public class LocationEventsListener {

    /** Producing domain, per the repo-wide processed_events convention (manifest scans key on it). */
    static final String OWNER = "location";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtLocationReplicaRepository extLocationReplicaRepository;
    private final ExtLocationParentReplicaRepository extLocationParentReplicaRepository;
    private final LocationHierarchyService locationHierarchyService;
    private final Counter payloadRejectedCounter;

    /** The apply and its processed mark in one transaction; a failure's mark in its own. */
    private final TransactionTemplate handlerTransaction;

    public LocationEventsListener(
            Clock clock,
            ObjectMapper objectMapper,
            ProcessedEventRepository processedEventRepository,
            ExtLocationReplicaRepository extLocationReplicaRepository,
            ExtLocationParentReplicaRepository extLocationParentReplicaRepository,
            LocationHierarchyService locationHierarchyService,
            ObjectProvider<MeterRegistry> meterRegistry,
            PlatformTransactionManager transactionManager) {
        this.clock = clock;
        this.objectMapper = objectMapper;
        this.processedEventRepository = processedEventRepository;
        this.extLocationReplicaRepository = extLocationReplicaRepository;
        this.extLocationParentReplicaRepository = extLocationParentReplicaRepository;
        this.locationHierarchyService = locationHierarchyService;
        MeterRegistry registry = meterRegistry.getIfAvailable();
        this.payloadRejectedCounter = registry == null
                ? null
                : Counter.builder("replica.payload.rejected")
                        .description(
                                "Replica event payloads rejected due to Jackson databind failures (e.g. a malformed identifier)")
                        .tag("owner", OWNER)
                        .tag("entity", "location-events")
                        .register(registry);
        this.handlerTransaction = new TransactionTemplate(transactionManager);
        this.handlerTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @KafkaListener(
            topics = "${pos.catalog.kafka.location-events-topic:location.events.v1}",
            groupId = "${pos.catalog.kafka.location-events-consumer-group:pos-catalog-location-events}")
    public void onLocationEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable location event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping location event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            handlerTransaction.executeWithoutResult(_ -> {
                switch (eventType == null ? "" : eventType) {
                    case LocationUpdatedV1.EVENT_TYPE -> applyLocationUpdated(envelope);
                    case LocationDeletedV1.EVENT_TYPE -> applyLocationDeleted(envelope);
                    // Ignored types (storage-location, bay and mobile-unit facts) still fall through
                    // to the processed_events insert below — see the class javadoc.
                    default -> log.debug("Ignoring location event type={} eventId={}", eventType, eventId);
                }
                recordProcessed(eventId, OWNER);
            });
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (DatabindException e) {
            if (payloadRejectedCounter != null) {
                payloadRejectedCounter.increment();
            }
            log.error("Rejected malformed location event payload eventId={}: {}", eventId, e.getMessage(), e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId, OWNER));
        } catch (Exception e) {
            log.warn("Skipping malformed location event eventId={}", eventId, e);
            handlerTransaction.executeWithoutResult(_ -> recordProcessed(eventId, OWNER));
        }
    }

    /** Records the eventId as processed, inside whichever transaction the caller runs. */
    private void recordProcessed(@NonNull String eventId, @NonNull String owner) {
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(owner)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyLocationUpdated(JsonNode envelope) {
        LocationUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), LocationUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtLocationReplica existing =
                extLocationReplicaRepository.findById(payload.locationId()).orElse(null);
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            return;
        }
        extLocationReplicaRepository.save(ExtLocationReplica.builder()
                .locationId(payload.locationId())
                .name(payload.name())
                .active(payload.active())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());

        // The fact carries the child's full typed parent-edge set — replace, don't merge.
        // A null list means the producer predates the field; leave existing edges untouched.
        List<LocationUpdatedV1.ParentRef> parents = payload.parents();
        if (parents != null) {
            extLocationParentReplicaRepository.deleteByChildId(payload.locationId());
            parents.forEach(edge -> extLocationParentReplicaRepository.save(ExtLocationParentReplica.builder()
                    .childId(payload.locationId())
                    .parentId(edge.parentId())
                    .parentType(edge.parentType())
                    .build()));
        }
        // Edges (or the row itself) may have changed: rebuild the scope ancestor sets for this
        // location and everything replicated beneath it (ADR-0061 §2).
        locationHierarchyService.recomputeAncestors(payload.locationId());
        log.info("Updated ext_location locationId={} version={}", payload.locationId(), aggregateVersion);
    }

    private void applyLocationDeleted(JsonNode envelope) {
        LocationDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), LocationDeletedV1.class);
        extLocationReplicaRepository.deleteById(payload.locationId());
        extLocationParentReplicaRepository.deleteByChildId(payload.locationId());
        log.info("Deleted ext_location locationId={}", payload.locationId());
    }
}
