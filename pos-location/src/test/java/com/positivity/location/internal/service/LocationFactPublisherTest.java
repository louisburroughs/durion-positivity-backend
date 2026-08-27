package com.positivity.location.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.DomainEventEnvelope;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.location.StorageLocationUpdatedV1;
import com.positivity.location.internal.config.OutboxEventWriter;
import com.positivity.location.internal.entity.Location;
import com.positivity.location.internal.entity.LocationParent;
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

        verify(writer, never()).publish(any(), any());
        verify(entityManager, never()).flush();
    }
}
