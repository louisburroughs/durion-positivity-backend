package com.positivity.inventory.internal.service;

import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.location.StorageLocationUpdatedV1;
import com.positivity.inventory.internal.entity.ExtLocationParentReplica;
import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
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
 * Consumes {@code location.events.v1} into the inventory location replicas (ADR-0044 §6, #892):
 * {@code location_ref} (roster + site defaults — the event-driven successor of the retired
 * {@code LocationRosterClient} bulk sync), {@code ext_location_parent} (typed hierarchy edges for
 * descendant queries), and {@code ext_storage_location} (topology + putaway/reservation
 * validation).
 *
 * <p>Phase 3.4 consumer contract: {@code processed_events} idempotency in the apply transaction,
 * strictly-below stale guard on the emission-timestamp {@code aggregateVersion}, transient DB
 * errors rethrown for container retry/DLQ. Unsupported event types still record their eventId —
 * the owner's manifest counts every fact in the window.
 *
 * <p>Location deletion marks {@code location_ref} inactive instead of deleting the row: inventory
 * records reference {@code locationId} and the sync flow always modelled removal as
 * deactivation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "pos.inventory.kafka", name = "enabled", havingValue = "true")
public class LocationEventsListener {

    static final String OWNER = "location";
    private static final String STATUS_DELETED = "DELETED";

    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final LocationRefRepository locationRefRepository;
    private final ExtLocationParentReplicaRepository extLocationParentReplicaRepository;
    private final ExtStorageLocationReplicaRepository extStorageLocationReplicaRepository;

    @KafkaListener(
            topics = "${pos.inventory.kafka.location-events-topic:location.events.v1}",
            groupId = "${pos.inventory.kafka.location-events-consumer-group:pos-inventory-location-events}")
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
        String eventId = envelope.path("eventId").stringValue(null);
        if (eventId == null || eventId.isBlank()) {
            log.warn("Skipping location event without eventId: {}", message);
            return;
        }
        if (processedEventRepository.existsById(eventId)) {
            return;
        }

        try {
            switch (eventType == null ? "" : eventType) {
                case LocationUpdatedV1.EVENT_TYPE -> applyLocationUpdated(envelope);
                case LocationDeletedV1.EVENT_TYPE -> applyLocationDeleted(envelope);
                case StorageLocationUpdatedV1.EVENT_TYPE -> applyStorageLocationUpdated(envelope);
                // Ignored types still fall through to the processed_events insert below: the
                // owner's manifest counts every fact in the window.
                default -> log.debug("Ignoring location event type={} eventId={}", eventType, eventId);
            }
        } catch (TransientDataAccessException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Skipping malformed location event eventId={}", eventId, e);
        }
        processedEventRepository.save(ProcessedEvent.builder()
                .eventId(eventId)
                .owner(OWNER)
                .processedAt(Instant.now(clock))
                .build());
    }

    private void applyLocationUpdated(JsonNode envelope) {
        LocationUpdatedV1 payload = objectMapper.treeToValue(envelope.path("payload"), LocationUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        LocationRefEntity ref =
                locationRefRepository.findByLocationId(payload.locationId()).orElse(null);
        if (ref != null && ref.getAggregateVersion() > aggregateVersion) {
            return;
        }
        boolean active = payload.active();
        if (ref == null) {
            ref = LocationRefEntity.builder()
                    .locationId(payload.locationId())
                    .active(active)
                    .build();
        }
        ref.setName(payload.name());
        ref.setCode(payload.code());
        ref.setStatus(payload.status() == null ? (active ? "ACTIVE" : "INACTIVE") : payload.status());
        ref.setHrLocationId(payload.hrLocationId());
        ref.setTimezone(payload.timezone());
        if (ref.isActive() && !active) {
            ref.setDeactivatedAt(clock.instant());
        } else if (active) {
            ref.setDeactivatedAt(null);
        }
        ref.setActive(active);
        ref.setSourceUpdatedAt(payload.updatedAt());
        ref.setDefaultStagingLocationId(payload.defaultStagingLocationId());
        ref.setDefaultQuarantineLocationId(payload.defaultQuarantineLocationId());
        ref.setAggregateVersion(aggregateVersion);
        locationRefRepository.save(ref);

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
        log.info("Updated location_ref locationId={} version={}", payload.locationId(), aggregateVersion);
    }

    private void applyLocationDeleted(JsonNode envelope) {
        LocationDeletedV1 payload = objectMapper.treeToValue(envelope.path("payload"), LocationDeletedV1.class);
        locationRefRepository.findByLocationId(payload.locationId()).ifPresent(ref -> {
            ref.setActive(false);
            ref.setStatus(STATUS_DELETED);
            ref.setDeactivatedAt(clock.instant());
            locationRefRepository.save(ref);
        });
        extLocationParentReplicaRepository.deleteByChildId(payload.locationId());
        log.info("Deactivated location_ref locationId={}", payload.locationId());
    }

    private void applyStorageLocationUpdated(JsonNode envelope) {
        StorageLocationUpdatedV1 payload =
                objectMapper.treeToValue(envelope.path("payload"), StorageLocationUpdatedV1.class);
        long aggregateVersion = envelope.path("aggregateVersion").longValue(0);
        ExtStorageLocationReplica existing = extStorageLocationReplicaRepository
                .findById(payload.storageLocationId())
                .orElse(null);
        if (existing != null && existing.getAggregateVersion() > aggregateVersion) {
            return;
        }
        extStorageLocationReplicaRepository.save(ExtStorageLocationReplica.builder()
                .storageLocationId(payload.storageLocationId())
                .siteId(payload.siteId())
                .name(payload.name())
                .barcode(payload.barcode())
                .type(payload.storageLocationType())
                .status(payload.status())
                .parentStorageLocationId(payload.parentStorageLocationId())
                .maxUnitCapacity(payload.maxUnitCapacity())
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.now(clock))
                .build());
        log.info(
                "Updated ext_storage_location storageLocationId={} version={}",
                payload.storageLocationId(),
                aggregateVersion);
    }
}
