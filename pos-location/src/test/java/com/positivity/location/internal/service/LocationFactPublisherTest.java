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
import com.positivity.location.internal.entity.StorageLocationEntity;
import com.positivity.location.internal.enums.StorageLocationStatus;
import com.positivity.location.internal.enums.StorageLocationType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

    private LocationFactPublisher publisher;

    @BeforeEach
    void setUp() {
        when(writerProvider.getIfAvailable()).thenReturn(writer);
        publisher = new LocationFactPublisher(writerProvider, TEST_CLOCK, "location.events.v1");
    }

    @Test
    @DisplayName("Location fact carries the tax address and site defaults")
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
        StorageLocationEntity staging = StorageLocationEntity.builder()
                .id(UUID.randomUUID())
                .name("Staging")
                .build();
        location.setDefaultStagingLocation(staging);

        publisher.locationChanged(location);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(LocationUpdatedV1.EVENT_TYPE);
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
    }

    @Test
    @DisplayName("Location delete emits the deleted fact")
    void locationDeleted() {
        UUID id = UUID.randomUUID();

        publisher.locationDeleted(id);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(LocationDeletedV1.EVENT_TYPE);
        assertThat(((LocationDeletedV1) captor.getValue().payload()).locationId())
                .isEqualTo(id);
    }

    @Test
    @DisplayName("Storage-location fact carries site + parent topology")
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
                .build();

        publisher.storageLocationChanged(storage);

        ArgumentCaptor<DomainEventEnvelope<?>> captor = ArgumentCaptor.forClass(DomainEventEnvelope.class);
        verify(writer).publish(eq("location.events.v1"), captor.capture());
        assertThat(captor.getValue().eventType()).isEqualTo(StorageLocationUpdatedV1.EVENT_TYPE);
        StorageLocationUpdatedV1 fact =
                (StorageLocationUpdatedV1) captor.getValue().payload();
        assertThat(fact.storageLocationId()).isEqualTo(storage.getId());
        assertThat(fact.siteId()).isEqualTo(site.getId());
        assertThat(fact.parentStorageLocationId()).isEqualTo(parent.getId());
        assertThat(fact.storageLocationType()).isEqualTo("BIN");
        assertThat(fact.status()).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("All methods no-op when Kafka publishing is disabled")
    void noopWhenWriterAbsent() {
        when(writerProvider.getIfAvailable()).thenReturn(null);

        publisher.locationChanged(new Location());
        publisher.locationDeleted(UUID.randomUUID());
        publisher.storageLocationChanged(
                StorageLocationEntity.builder().id(UUID.randomUUID()).build());

        verify(writer, never()).publish(any(), any());
    }
}
