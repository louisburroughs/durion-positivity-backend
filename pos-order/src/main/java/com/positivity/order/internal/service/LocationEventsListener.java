package com.positivity.order.internal.service;

import com.positivity.domainevents.ReplicaVersionGuard;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.order.internal.entity.ExtLocation;
import com.positivity.order.internal.entity.ExtLocationParentReplica;
import com.positivity.order.internal.entity.ProcessedEvent;
import com.positivity.order.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.order.internal.repository.ExtLocationRepository;
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
 * Consumes {@code location.events.v1} into the {@code ext_location} replica (ADR-0044 §6, parity
 * story B3): shop address for tax jurisdiction resolution, mirroring pos-workorder's ext_location
 * pattern (#892).
 *
 * <p>The stale guard on the fact's {@code aggregateVersion} is {@link ReplicaVersionGuard}
 * (#1486): pos-location's version strictly advances, so a held row is stale only when its version
 * is strictly greater than the incoming fact's — an equal version applies, both because it is an
 * idempotent no-op for live traffic and because it is what would let a future
 * regenerate-from-state replay repair a replica that holds the version number but wrong or
 * missing rows.
 *
 * <p>Each location fact also refreshes the materialised location-scope ancestor sets
 * (ADR-0061 §2, #1872): the child's typed parent edges — carried on the same
 * {@code location.location.updated} fact as {@code parents}; pos-location publishes no separate
 * parent-added/removed fact — are replaced from the fact, then
 * {@link LocationHierarchyService#recomputeAncestors} rebuilds the sets for the location and every
 * replicated descendant, so a re-parented node propagates and a parent arriving after its children
 * pushes its ancestry down to them. Ingestion never fails closed on a parent the replica has not
 * seen yet; the scope check does.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.order.kafka", name = "enabled", havingValue = "true")
public class LocationEventsListener {

    static final String OWNER = "location";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final ExtLocationRepository extLocationRepository;
    private final ExtLocationParentReplicaRepository extLocationParentReplicaRepository;
    private final LocationHierarchyService locationHierarchyService;

    @KafkaListener(
            topics = "${pos.order.kafka.location-events-topic:location.events.v1}",
            groupId = "${pos.order.kafka.location-events-consumer-group:pos-order-location-events}")
    @Transactional
    public void onLocationEvent(@NonNull String message) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(message);
        } catch (Exception e) {
            log.warn("Skipping unparsable location event: {}", message, e);
            return;
        }
        String eventType = envelope.path("eventType").stringValue(null);
        boolean update = LocationUpdatedV1.EVENT_TYPE.equals(eventType);
        boolean delete = LocationDeletedV1.EVENT_TYPE.equals(eventType);
        if (!update && !delete) {
            return;
        }
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping location event without eventId");
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
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
            log.warn("Skipping malformed location event eventId={}", eventId, e);
        }
    }

    private void applyUpdate(JsonNode envelope) {
        JsonNode payload = envelope.path("payload");
        UUID locationId = UUID.fromString(payload.path("locationId").stringValue(null));
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0L);

        ExtLocation existing = extLocationRepository.findById(locationId).orElse(null);
        // Strictly-newer-only skip: equal versions APPLY (#1486, ReplicaVersionGuard) — location's
        // aggregateVersion strictly advances, so equal means identical content, and a future replay
        // would resend the held version deliberately to repair a replica with wrong or missing rows.
        if (existing != null && ReplicaVersionGuard.isStale(existing.getAggregateVersion(), aggregateVersion)) {
            return;
        }
        ExtLocation replica = existing != null ? existing : new ExtLocation();
        replica.setLocationId(locationId);
        replica.setCode(payload.path("code").stringValue(null));
        replica.setName(payload.path("name").stringValue(null));
        replica.setActive(payload.path("active").booleanValue(true));
        replica.setAddressLine1(payload.path("addressLine1").stringValue(null));
        replica.setAddressLine2(payload.path("addressLine2").stringValue(null));
        replica.setCity(payload.path("city").stringValue(null));
        replica.setRegion(payload.path("region").stringValue(null));
        replica.setPostalCode(payload.path("postalCode").stringValue(null));
        replica.setCountry(payload.path("country").stringValue(null));
        replica.setAggregateVersion(aggregateVersion);
        replica.setSyncedAt(Instant.now(clock));
        extLocationRepository.save(replica);

        // The fact carries the child's full typed parent-edge set — replace, don't merge.
        // A missing/null list means the producer predates the field; leave existing edges
        // untouched. (Same contract as pos-inventory's, pos-people's and pos-invoice's replicas.)
        JsonNode parents = payload.path("parents");
        if (parents.isArray()) {
            extLocationParentReplicaRepository.deleteByChildId(locationId);
            for (JsonNode edge : parents) {
                extLocationParentReplicaRepository.save(ExtLocationParentReplica.builder()
                        .childId(locationId)
                        .parentId(UUID.fromString(edge.path("parentId").stringValue(null)))
                        .parentType(edge.path("parentType").stringValue(null))
                        .build());
            }
        }
        // Edges (or the row itself) may have changed: rebuild the scope ancestor sets for this
        // location and everything replicated beneath it (ADR-0061 §2, #1872).
        locationHierarchyService.recomputeAncestors(locationId);
    }

    private void applyDelete(JsonNode envelope) {
        UUID locationId =
                UUID.fromString(envelope.path("payload").path("locationId").stringValue(null));
        extLocationRepository.deleteById(locationId);
        extLocationParentReplicaRepository.deleteByChildId(locationId);
    }
}
