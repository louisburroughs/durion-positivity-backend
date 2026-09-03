package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.positivity.location.internal.entity.LocationParent;
import com.positivity.location.internal.entity.MobileUnitEntity;
import com.positivity.location.internal.entity.ParentType;
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.AllowNewProductPolicy;
import com.positivity.location.internal.enums.StorageCategory;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import com.positivity.location.internal.repository.LocationParentRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Unit tests for {@link LocationFactPublisher} (ADR-0044 §6, #890).
 */
class LocationFactPublisherTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-07-13T12:00:00Z"), ZoneOffset.UTC);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<OutboxEventWriter> writerProvider = mock(ObjectProvider.class);

    private final OutboxEventWriter writer = mock(OutboxEventWriter.class);
    private final LocationParentRepository locationParentRepository = mock(LocationParentRepository.class);
    private final EntityManager entityManager = mock(EntityManager.class);

    private LocationFactPublisher publisher;

    @BeforeEach
    void setUp() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        when(locationParentRepository.findByChild_Id(any())).thenReturn(List.of());
        publisher = new LocationFactPublisher(
                writerProvider, locationParentRepository, TEST_CLOCK, "location.events.v1", entityManager);
    }

    @Test
    @DisplayName("Location fact carries the tax address and site defaults, versioned from the flushed @Version (#1486)")
    void locationFact() {
        Location location = new Location();
        location.setId(UUID.randomUUID());
        location.setName("Main Shop");
        location.setCode("MAIN");
        location.setStatus("ACTIVE");
        location.setActive(true);
        location.setTimezone("America/New_York");
        location.setAddressLine1("1 Main St");
        location.setCity("Springfield");
        location.setState("VA");
        location.setPostalCode("22150");
        location.setCountry("US");
        // Deliberately distinct from any clock-derived value, so a test asserting on the retired
        // emission-timestamp convention would fail loudly rather than passing by coincidence.
        location.setVersion(42L);
        StorageLocationEntity staging = StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name("Staging")
                .build();
        location.setDefaultStagingLocation(staging);
        Location parentSite = new Location();
        parentSite.setId(UUID.randomUUID());
        when(locationParentRepository.findByChild_Id(location.getId()))
                .thenReturn(List.of(LocationParent.builder()
                        .child(location)
                        .parent(parentSite)
                        .parentType(ParentType.PHYSICAL)
                        .build()));

        publisher.locationChanged(location);

        // Flushed before the version is read (#1486), so the fact carries the entity's current
        // @Version rather than the retired Instant.now(clock)-stamped emission timestamp.
        verify(entityManager).flush();
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(LocationUpdatedV1.EVENT_TYPE);
        assertThat(captor.getValue().aggregateVersion()).isEqualTo(42L);
        LocationUpdatedV1 fact = (LocationUpdatedV1) captor.getValue().payload();
        assertThat(fact.locationId()).isEqualTo(location.getId());
        assertThat(fact.name()).isEqualTo("Main Shop");
        assertThat(fact.active()).isTrue();
        assertThat(fact.addressLine1()).isEqualTo("1 Main St");
        assertThat(fact.region()).isEqualTo("VA");
        assertThat(fact.postalCode()).isEqualTo("22150");
        assertThat(fact.country()).isEqualTo("US");
        assertThat(fact.defaultStagingLocationId()).isEqualTo(staging.getId());
        assertThat(fact.defaultQuarantineLocationId()).isNull();
        assertThat(fact.parents()).containsExactly(new LocationUpdatedV1.ParentRef(parentSite.getId(), "PHYSICAL"));
    }

    @Test
    @DisplayName("Location delete emits the deleted fact versioned as version + 1 (#1486)")
    void locationDeleted() {
        Location location = new Location();
        location.setId(UUID.randomUUID());
        location.setVersion(9L);

        publisher.locationDeleted(location);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(LocationDeletedV1.EVENT_TYPE);
        // Deterministic tombstone version (#1486) — one past every fact this aggregate has ever
        // published, without needing a clock comparison or a flush (the row is being deleted, not
        // re-saved, so there is no pending increment to pick up).
        assertThat(captor.getValue().aggregateVersion()).isEqualTo(10L);
        assertThat(((LocationDeletedV1) captor.getValue().payload()).locationId())
                .isEqualTo(location.getId());
    }

    @Test
    @DisplayName("Storage-location fact carries site + parent topology, versioned from the flushed @Version (#1486)")
    void storageLocationFact() {
        Location site = new Location();
        site.setId(UUID.randomUUID());
        StorageLocationEntity parent =
                StorageLocationEntity.builder().id(UUID.randomUUID()).build();
        StorageLocationEntity storage = StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name("Bin A1")
                .barcode("B-A1")
                .type(StorageLocationType.BIN)
                .status(StorageLocationStatus.ACTIVE)
                .site(site)
                .parentStorageLocation(parent)
                .capacity("{\"maxUnitCount\": 12}")
                .storageCategoryCode(StorageCategory.SMALL_PARTS_BIN)
                .hazardContainment(true)
                .allowNewProduct(AllowNewProductPolicy.SAME_PRODUCT_ONLY)
                .version(7L)
                .build();

        publisher.storageLocationChanged(storage);

        // Flushed before the version is read (#1486), so the fact carries the entity's current
        // @Version rather than the retired Instant.now(clock)-stamped emission timestamp.
        verify(entityManager).flush();
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(StorageLocationUpdatedV1.EVENT_TYPE);
        assertThat(captor.getValue().aggregateVersion()).isEqualTo(7L);
        StorageLocationUpdatedV1 fact =
                (StorageLocationUpdatedV1) captor.getValue().payload();
        assertThat(fact.storageLocationId()).isEqualTo(storage.getId());
        assertThat(fact.siteId()).isEqualTo(site.getId());
        assertThat(fact.parentStorageLocationId()).isEqualTo(parent.getId());
        assertThat(fact.storageLocationType()).isEqualTo("BIN");
        assertThat(fact.status()).isEqualTo("ACTIVE");
        assertThat(fact.maxUnitCapacity()).isEqualTo(12);
        // #1514: the putaway capability rides the existing fact additively so pos-inventory can
        // replicate it — no new synchronous call between the two services (ADR-0044).
        assertThat(fact.storageCategoryCode()).isEqualTo("SMALL_PARTS_BIN");
        assertThat(fact.hazardContainment()).isTrue();
        assertThat(fact.allowNewProduct()).isEqualTo("SAME_PRODUCT_ONLY");
    }

    @Test
    @DisplayName("#1514 - a storage location with no declared capability publishes GENERAL, not null")
    void storageLocationFactResolvesUndeclaredCapabilityToGeneral() {
        StorageLocationEntity storage = StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name("Legacy Bin")
                .type(StorageLocationType.BIN)
                .status(StorageLocationStatus.ACTIVE)
                .version(1L)
                .build();

        publisher.storageLocationChanged(storage);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        StorageLocationUpdatedV1 fact =
                (StorageLocationUpdatedV1) captor.getValue().payload();
        // Consumers get the same permissive default the owner's read path applies, so a replica
        // never has to know the null-means-GENERAL rule.
        assertThat(fact.storageCategoryCode()).isEqualTo("GENERAL");
        assertThat(fact.hazardContainment()).isFalse();
        assertThat(fact.allowNewProduct()).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("All methods no-op when Kafka publishing is disabled")
    void noopWhenWriterAbsent() {
        when(writerProvider.getIfAvailable()).thenReturn(null);
        Location location = new Location();
        location.setId(UUID.randomUUID());

        publisher.locationChanged(location);
        publisher.locationDeleted(location);
        publisher.storageLocationChanged(
                StorageLocationEntity.builder().id(UUID.randomUUID()).build());
        publisher.bayChanged(bay(UUID.randomUUID(), UUID.randomUUID(), "Bay", "ACTIVE", 1L));
        publisher.bayDeleted(bay(UUID.randomUUID(), UUID.randomUUID(), "Bay", "ACTIVE", 1L));
        publisher.mobileUnitChanged(mobileUnit(UUID.randomUUID(), UUID.randomUUID(), "Van", "ACTIVE", 1L));
        publisher.mobileUnitDeleted(mobileUnit(UUID.randomUUID(), UUID.randomUUID(), "Van", "ACTIVE", 1L));

        verify(writer, never()).publish(any(), any());
        verify(entityManager, never()).flush();
    }

    // ---------------------------------------------------------------------------------------
    // Bay and mobile-unit facts (issue #1668)
    // ---------------------------------------------------------------------------------------

    @Test
    @DisplayName("#1668 bay create publishes the owning site, name, type and raw status")
    void bayCreateFact() {
        UUID bayId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        publisher.bayChanged(bay(bayId, locationId, "Front Bay 1", "ACTIVE", 3L));

        BayUpdatedV1 fact = capturePayload(BayUpdatedV1.EVENT_TYPE, 3L, BayUpdatedV1.class);
        assertThat(fact.bayId()).isEqualTo(bayId);
        assertThat(fact.locationId()).isEqualTo(locationId);
        assertThat(fact.name()).isEqualTo("Front Bay 1");
        assertThat(fact.bayType()).isEqualTo("SERVICE");
        assertThat(fact.status()).isEqualTo("ACTIVE");
        // The version must be read after the pending @Version increment is applied (#1486).
        verify(entityManager).flush();
    }

    @Test
    @DisplayName("#1668 bay update carries the owning site even when the site is not what changed")
    void bayUpdateAlwaysCarriesSite() {
        UUID bayId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();

        publisher.bayChanged(bay(bayId, locationId, "Renamed Bay", "ACTIVE", 4L));

        BayUpdatedV1 fact = capturePayload(BayUpdatedV1.EVENT_TYPE, 4L, BayUpdatedV1.class);
        assertThat(fact.name()).isEqualTo("Renamed Bay");
        // Consumers rebuild the whole replica row from this payload, so a fact omitting the site
        // would blank the column their roster query filters on. pos-workorder rejects such a fact.
        assertThat(fact.locationId()).isEqualTo(locationId);
    }

    @Test
    @DisplayName("#1668 bay status change publishes the raw lifecycle string, never a derived boolean")
    void bayStatusChangeIsRaw() {
        UUID bayId = UUID.randomUUID();

        publisher.bayChanged(bay(bayId, UUID.randomUUID(), "Front Bay 1", "OUT_OF_SERVICE", 5L));

        BayUpdatedV1 fact = capturePayload(BayUpdatedV1.EVENT_TYPE, 5L, BayUpdatedV1.class);
        // Both consumers derive activeness with their own allow-list on ACTIVE. An invented
        // `active` boolean here was a real bug on the consumer side before review caught it.
        assertThat(fact.status()).isEqualTo("OUT_OF_SERVICE");
        assertThat(BayUpdatedV1.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("active");
    }

    @Test
    @DisplayName("#1668 bay tombstone is versioned one past every fact the aggregate published")
    void bayDeleteFact() {
        UUID bayId = UUID.randomUUID();

        publisher.bayDeleted(bay(bayId, UUID.randomUUID(), "Front Bay 1", "ACTIVE", 10L));

        // Consumers delete the replica row without consulting a version, so the tombstone must
        // outrank every update, including one still in flight.
        BayDeletedV1 fact = capturePayload(BayDeletedV1.EVENT_TYPE, 11L, BayDeletedV1.class);
        assertThat(fact.bayId()).isEqualTo(bayId);
        // A tombstone reads the version it already holds; there is no pending mutation to flush.
        verify(entityManager, never()).flush();
    }

    @Test
    @DisplayName("#1668 mobile unit create publishes the base site and raw status")
    void mobileUnitCreateFact() {
        UUID unitId = UUID.randomUUID();
        UUID baseLocationId = UUID.randomUUID();

        publisher.mobileUnitChanged(mobileUnit(unitId, baseLocationId, "Van 1", "ACTIVE", 2L));

        MobileUnitUpdatedV1 fact = capturePayload(MobileUnitUpdatedV1.EVENT_TYPE, 2L, MobileUnitUpdatedV1.class);
        assertThat(fact.mobileUnitId()).isEqualTo(unitId);
        assertThat(fact.baseLocationId()).isEqualTo(baseLocationId);
        assertThat(fact.name()).isEqualTo("Van 1");
        assertThat(fact.status()).isEqualTo("ACTIVE");
        verify(entityManager).flush();
    }

    @Test
    @DisplayName("#1668 mobile unit status change publishes INACTIVE rather than removing the unit")
    void mobileUnitStatusChangeIsRaw() {
        publisher.mobileUnitChanged(mobileUnit(UUID.randomUUID(), UUID.randomUUID(), "Van 1", "INACTIVE", 6L));

        MobileUnitUpdatedV1 fact = capturePayload(MobileUnitUpdatedV1.EVENT_TYPE, 6L, MobileUnitUpdatedV1.class);
        // Standing a unit down is a status change, not a tombstone: the replica keeps the row and
        // flips it inactive.
        assertThat(fact.status()).isEqualTo("INACTIVE");
        assertThat(MobileUnitUpdatedV1.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .doesNotContain("active");
    }

    @Test
    @DisplayName("#1668 a mobile unit re-based from A to B publishes B, so a consumer moves the row")
    void mobileUnitRebasePublishesNewSite() {
        UUID unitId = UUID.randomUUID();
        UUID siteA = UUID.randomUUID();
        UUID siteB = UUID.randomUUID();

        publisher.mobileUnitChanged(mobileUnit(unitId, siteA, "Van 1", "ACTIVE", 7L));
        publisher.mobileUnitChanged(mobileUnit(unitId, siteB, "Van 1", "ACTIVE", 8L));

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer, org.mockito.Mockito.times(2)).publish(eq("location.events.v1"), captor.capture());

        MobileUnitUpdatedV1 before =
                (MobileUnitUpdatedV1) captor.getAllValues().get(0).payload();
        MobileUnitUpdatedV1 after =
                (MobileUnitUpdatedV1) captor.getAllValues().get(1).payload();
        assertThat(before.baseLocationId()).isEqualTo(siteA);
        // The re-base travels on an ordinary update naming the new site. Because consumers rebuild
        // the row from the payload and scope rosters by this column, the unit leaves A's roster and
        // joins B's -- no delete+insert, which an out-of-order pair could turn into a lost row.
        assertThat(after.baseLocationId()).isEqualTo(siteB);
        assertThat(after.mobileUnitId()).isEqualTo(unitId);
        // The aggregate id is the Kafka record key, so both facts land on one partition in order.
        assertThat(captor.getAllValues().get(1).recordKey()).isEqualTo(unitId.toString());
        assertThat(captor.getAllValues().get(1).aggregateVersion())
                .isGreaterThan(captor.getAllValues().get(0).aggregateVersion());
    }

    @Test
    @DisplayName("#1668 mobile unit tombstone is versioned one past every fact the aggregate published")
    void mobileUnitDeleteFact() {
        UUID unitId = UUID.randomUUID();

        publisher.mobileUnitDeleted(mobileUnit(unitId, UUID.randomUUID(), "Van 1", "ACTIVE", 20L));

        MobileUnitDeletedV1 fact = capturePayload(MobileUnitDeletedV1.EVENT_TYPE, 21L, MobileUnitDeletedV1.class);
        assertThat(fact.mobileUnitId()).isEqualTo(unitId);
        verify(entityManager, never()).flush();
    }

    @Test
    @DisplayName("#1668 bay and mobile-unit facts ride the same topic and source as every location fact")
    void newFactsUseTheEstablishedEnvelope() {
        publisher.bayChanged(bay(UUID.randomUUID(), UUID.randomUUID(), "Front Bay 1", "ACTIVE", 1L));

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        assertThat(captor.getValue().sourceService()).isEqualTo("pos-location");
        assertThat(captor.getValue().schemaVersion()).isEqualTo(BayUpdatedV1.SCHEMA_VERSION);
        assertThat(captor.getValue().occurredAtUtc()).isEqualTo(Instant.parse("2026-07-13T12:00:00Z"));
    }

    /** Capture the single published envelope, assert its type and version, and return the payload. */
    private <T> T capturePayload(String expectedEventType, long expectedVersion, Class<T> payloadType) {
        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(expectedEventType);
        assertThat(captor.getValue().aggregateVersion()).isEqualTo(expectedVersion);
        return payloadType.cast(captor.getValue().payload());
    }

    private static BayEntity bay(UUID id, UUID locationId, String name, String status, long version) {
        Location location = new Location();
        location.setId(locationId);
        BayEntity entity = BayEntity.builder()
                .id(id)
                .location(location)
                .name(name)
                .bayType("SERVICE")
                .status(status)
                .maxConcurrentVehicles(1)
                .build();
        entity.setVersion(version);
        return entity;
    }

    private static MobileUnitEntity mobileUnit(UUID id, UUID baseLocationId, String name, String status, long version) {
        Location baseLocation = new Location();
        baseLocation.setId(baseLocationId);
        MobileUnitEntity entity = MobileUnitEntity.builder()
                .id(id)
                .baseLocation(baseLocation)
                .name(name)
                .status(status)
                .build();
        entity.setVersion(version);
        return entity;
    }
}
