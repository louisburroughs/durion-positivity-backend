package com.positivity.location.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.location.StorageLocationUpdatedV1;
import com.positivity.location.internal.config.OutboxEventWriter;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.repository.LocationParentRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes location-owned facts to {@code location.events.v1} through the transactional outbox
 * (ADR-0044 §6, issue #890 Phase 4.2).
 *
 * <p>Every location/storage-location mutation site calls {@link #locationChanged},
 * {@link #locationDeleted} or {@link #storageLocationChanged} inside its business transaction.
 * When Kafka publishing is disabled ({@code pos.location.kafka.enabled=false}) the outbox writer
 * bean is absent and every method is a no-op, so callers never need their own guard.
 *
 * <p>The envelope's {@code aggregateVersion} is the emission timestamp in epoch millis — the
 * platform-wide last-writer-wins hint consumers apply their strictly-below stale guard to.
 */
@Slf4j
@Component
public class LocationFactPublisher {

    private static final String SOURCE = "pos-location";

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final LocationParentRepository locationParentRepository;
    private final Clock clock;
    private final String eventsTopic;

    public LocationFactPublisher(
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            LocationParentRepository locationParentRepository,
            Clock clock,
            // Same property ManifestPublisher scans event_outbox by, so an override can never
            // desync the outbox rows from the manifest computation.
            @Value("${pos.location.kafka.events-topic:location.events.v1}") String eventsTopic) {
        this.outboxEventWriter = outboxEventWriter;
        this.locationParentRepository = locationParentRepository;
        this.clock = clock;
        this.eventsTopic = eventsTopic;
    }

    /** Emit {@code location.location.updated} for a just-saved location. */
    public void locationChanged(@NonNull Location location) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        LocationUpdatedV1 payload = new LocationUpdatedV1(
                location.getId(),
                location.getName(),
                location.getCode(),
                location.getStatus(),
                location.isActive(),
                location.getType() != null ? location.getType().getName() : null,
                location.getHrLocationId(),
                location.getTimezone(),
                location.getAddressLine1(),
                location.getAddressLine2(),
                location.getCity(),
                location.getState(),
                location.getPostalCode(),
                location.getCountry(),
                location.getDefaultStagingLocation() != null
                        ? location.getDefaultStagingLocation().getId()
                        : null,
                location.getDefaultQuarantineLocation() != null
                        ? location.getDefaultQuarantineLocation().getId()
                        : null,
                parentRefs(location.getId()),
                location.getCreatedAt(),
                location.getUpdatedAt());
        publish(writer, LocationUpdatedV1.EVENT_TYPE, location.getId(), payload);
    }

    /** Emit {@code location.location.deleted} for a location removed in the current transaction. */
    public void locationDeleted(@NonNull UUID locationId) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        publish(writer, LocationDeletedV1.EVENT_TYPE, locationId, new LocationDeletedV1(locationId));
    }

    /**
     * Emit {@code location.storage-location.updated} for a just-saved storage location. Storage
     * locations are never hard-deleted — decommissioning is a status change on this same fact.
     */
    public void storageLocationChanged(@NonNull StorageLocationEntity storageLocation) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        StorageLocationUpdatedV1 payload = new StorageLocationUpdatedV1(
                storageLocation.getId(),
                storageLocation.getSite() != null ? storageLocation.getSite().getId() : null,
                storageLocation.getName(),
                storageLocation.getBarcode(),
                storageLocation.getType() != null ? storageLocation.getType().name() : null,
                storageLocation.getStatus() != null
                        ? storageLocation.getStatus().name()
                        : null,
                storageLocation.getParentStorageLocation() != null
                        ? storageLocation.getParentStorageLocation().getId()
                        : null,
                storageLocation.getCapacity(),
                StorageCapacityJson.extractMaxUnitCapacity(storageLocation.getCapacity()),
                storageLocation.getTemperature(),
                storageLocation.getCreatedAt(),
                storageLocation.getUpdatedAt());
        publish(writer, StorageLocationUpdatedV1.EVENT_TYPE, storageLocation.getId(), payload);
    }

    private List<LocationUpdatedV1.ParentRef> parentRefs(@NonNull UUID locationId) {
        return locationParentRepository.findByChild_Id(locationId).stream()
                .map(edge -> new LocationUpdatedV1.ParentRef(
                        edge.getParent().getId(), edge.getParentType().name()))
                .toList();
    }

    private void publish(
            @NonNull OutboxEventWriter writer, @NonNull String eventType, @NonNull UUID aggregateId, Object payload) {
        DomainEventEnvelope<Object> envelope = DomainEventEnvelope.of(
                eventType, 1, aggregateId, Instant.now(clock).toEpochMilli(), SOURCE, null, null, payload, clock);
        writer.publish(eventsTopic, envelope);
        log.debug("Queued {} for aggregate {}", eventType, aggregateId);
    }
}
