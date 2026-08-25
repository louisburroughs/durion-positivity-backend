package com.positivity.location.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.location.StorageLocationUpdatedV1;
import com.positivity.location.internal.config.OutboxEventWriter;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.repository.LocationParentRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
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
 * <p>{@code Location} and {@code StorageLocationEntity} each carry a JPA {@code @Version}, and the
 * envelope's {@code aggregateVersion} is that counter (#1486): it strictly increments on every
 * committed mutation, so — unlike the retired {@code Instant.now(clock)}-stamped emission
 * timestamp — two mutations landing in the same millisecond can never tie. Migration V6 seeded
 * both columns from wall-clock millis at migration time (not {@code updated_at} — see the
 * migration header for why), so the published sequence continues above every version consumers
 * already hold. An update publisher flushes the pending mutation before reading the version, so
 * the increment Hibernate is about to apply is already reflected in the emitted fact. A consumer
 * skips a fact only when the version it already holds is strictly greater than the incoming one —
 * an equal version applies, both because it is an idempotent no-op for live traffic and because it
 * is what would let a future regenerate-from-state replay repair a replica that holds the version
 * number but wrong or missing data.
 */
@Slf4j
@Component
public class LocationFactPublisher {

    private static final String SOURCE = "pos-location";

    private final ObjectProvider<OutboxEventWriter> outboxEventWriter;
    private final LocationParentRepository locationParentRepository;
    private final Clock clock;
    private final String eventsTopic;
    private final EntityManager entityManager;

    public LocationFactPublisher(
            ObjectProvider<OutboxEventWriter> outboxEventWriter,
            LocationParentRepository locationParentRepository,
            Clock clock,
            // Same property ManifestPublisher scans event_outbox by, so an override can never
            // desync the outbox rows from the manifest computation.
            @Value("${pos.location.kafka.events-topic:location.events.v1}") String eventsTopic,
            EntityManager entityManager) {
        this.outboxEventWriter = outboxEventWriter;
        this.locationParentRepository = locationParentRepository;
        this.clock = clock;
        this.eventsTopic = eventsTopic;
        this.entityManager = entityManager;
    }

    /**
     * Emit {@code location.location.updated} for a just-saved location.
     *
     * <p>Flushed before reading {@code aggregateVersion}: the mutation that triggered this call is
     * still pending in the persistence context, and flushing here forces Hibernate to apply the
     * {@code @Version} increment so the envelope carries the version the row is about to commit as,
     * not the one it held before this write (#1486). {@code version} is null-safe-read as 0 for the
     * pre-persist in-memory edge only — a location that has actually gone through Hibernate always
     * has a non-null version by the time this method's flush returns.
     */
    public void locationChanged(@NonNull Location location) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        entityManager.flush();
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
        publish(
                writer,
                LocationUpdatedV1.EVENT_TYPE,
                LocationUpdatedV1.SCHEMA_VERSION,
                location.getId(),
                payload,
                location.getVersion() == null ? 0L : location.getVersion());
    }

    /**
     * Emit {@code location.location.deleted} for a location removed in the current transaction.
     *
     * <p>Takes the deleted {@code Location} entity, not just its id, so the fact can be versioned
     * deterministically as {@code version + 1} (#1486) — one past every fact this aggregate has
     * ever published — the same tombstone pattern pos-catalog uses. The caller must have loaded the
     * entity before deleting it; a delete of an id nothing was found for has nothing to version and
     * must not call this method at all (see {@code LocationServiceImpl.deleteLocation}).
     */
    public void locationDeleted(@NonNull Location location) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        long version = location.getVersion() == null ? 0L : location.getVersion();
        publish(
                writer,
                LocationDeletedV1.EVENT_TYPE,
                LocationDeletedV1.SCHEMA_VERSION,
                location.getId(),
                new LocationDeletedV1(location.getId()),
                version + 1);
    }

    /**
     * Emit {@code location.storage-location.updated} for a just-saved storage location. Storage
     * locations are never hard-deleted — decommissioning is a status change on this same fact.
     *
     * <p>Flushed before reading {@code aggregateVersion}, the same way {@link #locationChanged} is:
     * the pending {@code @Version} increment is applied by the flush, so the envelope carries the
     * version the row is about to commit as (#1486).
     */
    public void storageLocationChanged(@NonNull StorageLocationEntity storageLocation) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        entityManager.flush();
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
        publish(
                writer,
                StorageLocationUpdatedV1.EVENT_TYPE,
                StorageLocationUpdatedV1.SCHEMA_VERSION,
                storageLocation.getId(),
                payload,
                storageLocation.getVersion());
    }

    private List<LocationUpdatedV1.ParentRef> parentRefs(@NonNull UUID locationId) {
        return locationParentRepository.findByChild_Id(locationId).stream()
                .map(edge -> new LocationUpdatedV1.ParentRef(
                        edge.getParent().getId(), edge.getParentType().name()))
                .toList();
    }

    private void publish(
            @NonNull OutboxEventWriter writer,
            @NonNull String eventType,
            int schemaVersion,
            @NonNull UUID aggregateId,
            Object payload,
            long aggregateVersion) {
        DomainEventEnvelope<Object> envelope = DomainEventEnvelope.of(
                eventType, schemaVersion, aggregateId, aggregateVersion, SOURCE, null, null, payload, clock);
        writer.publish(eventsTopic, envelope);
        log.debug("Queued {} for aggregate {}", eventType, aggregateId);
    }
}
