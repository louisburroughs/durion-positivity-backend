package com.positivity.inventory.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.entity.ExtStorageLocationReplica;
import com.positivity.inventory.internal.entity.ProcessedEvent;
import com.positivity.inventory.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.inventory.internal.repository.ExtStorageLocationReplicaRepository;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Consumer contract of the {@code location.events.v1} listener for the {@code ext_storage_location}
 * replica, focused on the putaway capability trio pos-location adds to the storage-location fact
 * (#1514): {@code storageCategoryCode}, {@code hazardContainment}, {@code allowNewProduct}.
 *
 * <p>The trio is replicated verbatim — the replica mirrors upstream and never validates the code
 * against a local enum, so a storage category this build has never heard of still lands rather
 * than stalling the feed.
 */
class LocationEventsListenerTest {

    private static final Clock TEST_CLOCK = Clock.fixed(Instant.parse("2026-08-27T12:00:00Z"), ZoneOffset.UTC);
    private static final UUID STORAGE_LOCATION_ID = UUID.fromString("018f0000-0000-7000-8000-000000000d01");
    private static final UUID SITE_ID = UUID.fromString("018f0000-0000-7000-8000-000000000d02");

    private final ProcessedEventRepository processedEvents = mock(ProcessedEventRepository.class);
    private final LocationRefRepository locationRefs = mock(LocationRefRepository.class);
    private final ExtLocationParentReplicaRepository extLocationParents =
            mock(ExtLocationParentReplicaRepository.class);
    private final ExtStorageLocationReplicaRepository extStorageLocations =
            mock(ExtStorageLocationReplicaRepository.class);

    private LocationEventsListener listener;

    @BeforeEach
    void setUp() {
        listener = new LocationEventsListener(
                TEST_CLOCK,
                new ObjectMapper(),
                processedEvents,
                locationRefs,
                extLocationParents,
                extStorageLocations,
                org.mockito.Mockito.mock(ObjectProvider.class));
    }

    /** Storage-location fact carrying the capability trio. */
    private static String capabilityEvent(String eventId, long aggregateVersion, String storageCategoryCode) {
        return """
                {"eventId":"%s","eventType":"location.storage-location.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"storageLocationId":"%s","siteId":"%s","name":"Battery Rack A",
                            "barcode":"BR-A","storageLocationType":"SHELF","status":"ACTIVE",
                            "maxUnitCapacity":40,
                            "storageCategoryCode":"%s","hazardContainment":true,
                            "allowNewProduct":"SAME_PRODUCT_ONLY"}}
                """.formatted(
                eventId, STORAGE_LOCATION_ID, aggregateVersion, STORAGE_LOCATION_ID, SITE_ID, storageCategoryCode);
    }

    /** Fact from a producer that predates the capability trio. */
    private static String preCapabilityEvent(String eventId, long aggregateVersion) {
        return """
                {"eventId":"%s","eventType":"location.storage-location.updated","schemaVersion":1,
                 "aggregateId":"%s","aggregateVersion":%d,
                 "payload":{"storageLocationId":"%s","siteId":"%s","name":"Shelf 1",
                            "storageLocationType":"SHELF","status":"ACTIVE"}}
                """.formatted(eventId, STORAGE_LOCATION_ID, aggregateVersion, STORAGE_LOCATION_ID, SITE_ID);
    }

    private ExtStorageLocationReplica existingReplica(long aggregateVersion) {
        return ExtStorageLocationReplica.builder()
                .storageLocationId(STORAGE_LOCATION_ID)
                .siteId(SITE_ID)
                .name("Battery Rack A")
                .status("ACTIVE")
                .aggregateVersion(aggregateVersion)
                .updatedAt(Instant.parse("2026-08-26T00:00:00Z"))
                .build();
    }

    @Test
    @DisplayName("The capability trio lands on the ext_storage_location replica")
    void replicatesTheCapabilityTrio() {
        when(processedEvents.existsById("l-1")).thenReturn(false);
        when(extStorageLocations.findById(STORAGE_LOCATION_ID)).thenReturn(Optional.empty());

        listener.onLocationEvent(capabilityEvent("l-1", 100, "BATTERY_RACK"));

        ArgumentCaptor<ExtStorageLocationReplica> saved = ArgumentCaptor.captor();
        verify(extStorageLocations).save(saved.capture());
        assertThat(saved.getValue().getStorageCategoryCode()).isEqualTo("BATTERY_RACK");
        assertThat(saved.getValue().getHazardContainment()).isTrue();
        assertThat(saved.getValue().getAllowNewProduct()).isEqualTo("SAME_PRODUCT_ONLY");
        // Pre-existing fields keep working.
        assertThat(saved.getValue().getSiteId()).isEqualTo(SITE_ID);
        assertThat(saved.getValue().getType()).isEqualTo("SHELF");
        assertThat(saved.getValue().getMaxUnitCapacity()).isEqualTo(40);
        assertThat(saved.getValue().getAggregateVersion()).isEqualTo(100L);
    }

    @Test
    @DisplayName("An unrecognised storage category is replicated verbatim, not rejected")
    void unknownStorageCategoryIsReplicatedVerbatim() {
        when(processedEvents.existsById("l-unknown")).thenReturn(false);
        when(extStorageLocations.findById(STORAGE_LOCATION_ID)).thenReturn(Optional.empty());

        listener.onLocationEvent(capabilityEvent("l-unknown", 100, "CRYO_VAULT"));

        ArgumentCaptor<ExtStorageLocationReplica> saved = ArgumentCaptor.captor();
        verify(extStorageLocations).save(saved.capture());
        assertThat(saved.getValue().getStorageCategoryCode()).isEqualTo("CRYO_VAULT");
    }

    @Test
    @DisplayName("A producer predating the trio replicates nulls, not defaults")
    void preCapabilityFactReplicatesNulls() {
        when(processedEvents.existsById("l-old")).thenReturn(false);
        when(extStorageLocations.findById(STORAGE_LOCATION_ID)).thenReturn(Optional.empty());

        listener.onLocationEvent(preCapabilityEvent("l-old", 100));

        ArgumentCaptor<ExtStorageLocationReplica> saved = ArgumentCaptor.captor();
        verify(extStorageLocations).save(saved.capture());
        assertThat(saved.getValue().getStorageCategoryCode()).isNull();
        assertThat(saved.getValue().getHazardContainment()).isNull();
        assertThat(saved.getValue().getAllowNewProduct()).isNull();
    }

    @Test
    @DisplayName("The stale guard still holds: a lower-version fact cannot change the capability")
    void staleFactCannotChangeTheCapability() {
        when(processedEvents.existsById("l-stale")).thenReturn(false);
        when(extStorageLocations.findById(STORAGE_LOCATION_ID)).thenReturn(Optional.of(existingReplica(200)));

        listener.onLocationEvent(capabilityEvent("l-stale", 100, "GENERAL"));

        verify(extStorageLocations, never()).save(any());
        verify(processedEvents).save(any());
    }

    @Test
    @DisplayName("An equal-version fact applies, so a replay repairs a replica missing the trio")
    void equalVersionApplies() {
        when(processedEvents.existsById("l-equal")).thenReturn(false);
        when(extStorageLocations.findById(STORAGE_LOCATION_ID)).thenReturn(Optional.of(existingReplica(100)));

        listener.onLocationEvent(capabilityEvent("l-equal", 100, "BATTERY_RACK"));

        ArgumentCaptor<ExtStorageLocationReplica> saved = ArgumentCaptor.captor();
        verify(extStorageLocations).save(saved.capture());
        assertThat(saved.getValue().getStorageCategoryCode()).isEqualTo("BATTERY_RACK");
    }

    @Test
    @DisplayName("A duplicate eventId is skipped entirely, capability trio included")
    void skipsDuplicates() {
        when(processedEvents.existsById("l-dup")).thenReturn(true);

        listener.onLocationEvent(capabilityEvent("l-dup", 100, "BATTERY_RACK"));

        verify(extStorageLocations, never()).save(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Propagates transient DB errors so the container retries")
    void propagatesTransientErrors() {
        when(processedEvents.existsById("l-transient")).thenReturn(false);
        when(extStorageLocations.findById(STORAGE_LOCATION_ID)).thenReturn(Optional.empty());
        when(extStorageLocations.save(any())).thenThrow(new QueryTimeoutException("db"));

        assertThatExceptionOfType(QueryTimeoutException.class)
                .isThrownBy(() -> listener.onLocationEvent(capabilityEvent("l-transient", 100, "GENERAL")));

        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("Records the owner on the processed_events row")
    void recordsTheOwner() {
        when(processedEvents.existsById("l-owner")).thenReturn(false);
        when(extStorageLocations.findById(STORAGE_LOCATION_ID)).thenReturn(Optional.empty());

        listener.onLocationEvent(capabilityEvent("l-owner", 100, "GENERAL"));

        ArgumentCaptor<ProcessedEvent> processed = ArgumentCaptor.captor();
        verify(processedEvents).save(processed.capture());
        assertThat(processed.getValue().getOwner()).isEqualTo(LocationEventsListener.OWNER);
    }
}
