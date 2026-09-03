package com.positivity.location.internal.service;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.location.BayDeletedV1;
import com.positivity.domainevents.location.BayUpdatedV1;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.location.MobileUnitDeletedV1;
import com.positivity.domainevents.location.MobileUnitUpdatedV1;
import com.positivity.domainevents.location.StorageLocationUpdatedV1;
import com.positivity.location.internal.config.OutboxEventWriter;
import com.positivity.location.internal.entity.BayEntity;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.MobileUnitEntity;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.AllowNewProductPolicy;
import com.positivity.location.internal.enums.StorageCategory;
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
 * {@link #locationDeleted} or {@link #storageLocationChanged} inside its business transaction;
 * bay and mobile-unit sites call {@link #bayChanged}, {@link #bayDeleted},
 * {@link #mobileUnitChanged} or {@link #mobileUnitDeleted} the same way (issue #1668).
 * When Kafka publishing is disabled ({@code pos.location.kafka.enabled=false}) the outbox writer
 * bean is absent and every method is a no-op, so callers never need their own guard.
 *
 * <p>Bay and mobile-unit lifecycle facts (issue #1668) close the gap that left pos-workorder's and
 * pos-shop-manager's {@code ext_bay}/{@code ext_mobile_unit} replicas empty: both consumers were
 * built against these event names before any producer existed. {@code BayEntity} and
 * {@code MobileUnitEntity} gained their own {@code @Version} counters in migration V9 for exactly
 * the reason described below — a consumer may not version-guard a fact whose publisher has not
 * adopted the flush-then-read pattern.
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
                // Issue #1514: the capability rides this existing fact additively (ADR-0044 — no
                // new synchronous call to pos-location). An undeclared capability is resolved to
                // GENERAL here, the same as on the owner's own read path, so a replica never has
                // to reimplement the null-means-GENERAL rule.
                StorageCategory.orDefault(storageLocation.getStorageCategoryCode())
                        .name(),
                storageLocation.isHazardContainment(),
                AllowNewProductPolicy.orDefault(storageLocation.getAllowNewProduct())
                        .name(),
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

    /**
     * Emit {@code location.bay.updated} for a just-saved bay (issue #1668).
     *
     * <p>Flushed before reading {@code aggregateVersion}, the same way {@link #locationChanged} is:
     * the pending {@code @Version} increment is applied by the flush, so the envelope carries the
     * version the row is about to commit as (#1486).
     *
     * <p>{@code locationId} is read through {@code BayEntity.getLocationId()}, which dereferences
     * the lazy {@code location} association. The owning site travels on every emission, never only
     * on the mutation that changed it: consumers rebuild the entire replica row from this payload,
     * so omitting it would blank the column their roster query filters on. pos-workorder rejects
     * such a fact outright and pos-shop-manager would keep an unreachable row.
     *
     * <p>{@code status} is published raw. {@code BayEntity} has no active column, and a derived
     * boolean here would strand consumers that already resolve activeness with their own allow-list
     * on {@code ACTIVE}.
     */
    public void bayChanged(@NonNull BayEntity bay) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        entityManager.flush();
        BayUpdatedV1 payload =
                new BayUpdatedV1(bay.getId(), bay.getLocationId(), bay.getName(), bay.getBayType(), bay.getStatus());
        publish(writer, BayUpdatedV1.EVENT_TYPE, BayUpdatedV1.SCHEMA_VERSION, bay.getId(), payload, bay.getVersion());
    }

    /**
     * Emit {@code location.bay.deleted} for a bay removed in the current transaction (issue #1668).
     *
     * <p>Takes the deleted {@link BayEntity}, not just its id, so the fact can be versioned
     * deterministically as {@code version + 1} (#1486) — one past every fact this aggregate has
     * ever published — the same tombstone pattern {@link #locationDeleted} follows. Consumers
     * delete the replica row unconditionally, consulting no version, so a tombstone that did not
     * outrank every update could lose a race against one still in flight.
     *
     * <p>The caller must have loaded the entity before deleting it; a delete of an id nothing was
     * found for has nothing to version and must not call this method at all.
     */
    public void bayDeleted(@NonNull BayEntity bay) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        publish(
                writer,
                BayDeletedV1.EVENT_TYPE,
                BayDeletedV1.SCHEMA_VERSION,
                bay.getId(),
                new BayDeletedV1(bay.getId()),
                bay.getVersion() + 1);
    }

    /**
     * Emit {@code location.mobile-unit.updated} for a just-saved mobile unit (issue #1668).
     *
     * <p>Flushed before reading {@code aggregateVersion} for the same reason
     * {@link #locationChanged} is (#1486).
     *
     * <p>Carries {@code baseLocationId} on every emission, which is what makes a re-base
     * replicable: a unit moved from site A to site B publishes an ordinary update naming B, and
     * because consumers rebuild the row from the payload and scope their rosters by that column,
     * the unit leaves A's roster and joins B's on the next read. A re-base is deliberately not
     * expressed as a delete followed by an update — {@link #mobileUnitDeleted} is an unguarded
     * delete on the consumer side, so the pair could resurrect or drop the row if it arrived out of
     * order.
     *
     * <p>{@code status} is published raw ({@code ACTIVE} | {@code INACTIVE}), never a derived
     * boolean.
     */
    public void mobileUnitChanged(@NonNull MobileUnitEntity mobileUnit) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        entityManager.flush();
        MobileUnitUpdatedV1 payload = new MobileUnitUpdatedV1(
                mobileUnit.getId(), mobileUnit.getBaseLocationId(), mobileUnit.getName(), mobileUnit.getStatus());
        publish(
                writer,
                MobileUnitUpdatedV1.EVENT_TYPE,
                MobileUnitUpdatedV1.SCHEMA_VERSION,
                mobileUnit.getId(),
                payload,
                mobileUnit.getVersion());
    }

    /**
     * Emit {@code location.mobile-unit.deleted} for a mobile unit removed in the current
     * transaction (issue #1668).
     *
     * <p>Versioned {@code version + 1} for the same reason {@link #bayDeleted} is: consumers delete
     * the replica row without consulting a version, so the tombstone must outrank every update the
     * aggregate has published. Standing a unit down is a {@code status} change on
     * {@link #mobileUnitChanged}, not a tombstone.
     */
    public void mobileUnitDeleted(@NonNull MobileUnitEntity mobileUnit) {
        OutboxEventWriter writer = outboxEventWriter.getIfAvailable();
        if (writer == null) {
            return;
        }
        publish(
                writer,
                MobileUnitDeletedV1.EVENT_TYPE,
                MobileUnitDeletedV1.SCHEMA_VERSION,
                mobileUnit.getId(),
                new MobileUnitDeletedV1(mobileUnit.getId()),
                mobileUnit.getVersion() + 1);
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
