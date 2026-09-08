package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.location.StorageLocationUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtLocationParentReplica;
import com.positivity.shopmanager.internal.entity.ExtLocationReplica;
import com.positivity.shopmanager.internal.entity.ProcessedEvent;
import com.positivity.shopmanager.internal.repository.ExtBayReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtLocationReplicaRepository;
import com.positivity.shopmanager.internal.repository.ExtMobileUnitReplicaRepository;
import com.positivity.shopmanager.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * The location-fact half of {@link LocationEventsListener} (#1872): projection of
 * {@code location.location.updated} / {@code .deleted} into {@code ext_location} and
 * {@code ext_location_parent}, the edge-replacement contract, and the hand-off to
 * {@link LocationHierarchyService#recomputeAncestors}. The bay/mobile-unit half and the shared
 * consumer contract (dedup, stale guard, transient rethrow) are pinned in
 * {@link ReplicaAndManifestListenerContractTest}; the database-backed closure is in
 * {@link LocationHierarchyServiceTest}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("pos-shop-manager LocationEventsListener — location facts")
class LocationEventsListenerTest {

    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID PARENT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final Instant NOW = Instant.parse("2026-09-07T09:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtBayReplicaRepository extBayReplicaRepository;

    @Mock
    private ExtMobileUnitReplicaRepository extMobileUnitReplicaRepository;

    @Mock
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Mock
    private ExtLocationParentReplicaRepository extLocationParentReplicaRepository;

    @Mock
    private LocationHierarchyService locationHierarchyService;

    private LocationEventsListener listener;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        listener = new LocationEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                extBayReplicaRepository,
                extMobileUnitReplicaRepository,
                extLocationReplicaRepository,
                extLocationParentReplicaRepository,
                locationHierarchyService,
                Mockito.mock(ObjectProvider.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(extLocationReplicaRepository.findById(any())).thenReturn(Optional.empty());
    }

    private static String locationUpdated(String eventId, long version, boolean active) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,
                 "payload":{"locationId":"%s","name":"Main Shop","code":"SHOP-1","status":"OPEN",
                   "active":%s,"locationType":"SHOP","hrLocationId":null,"timezone":"America/Chicago",
                   "addressLine1":"1 Main St","addressLine2":null,"city":"Austin","region":"TX",
                   "postalCode":"78701","country":"US","defaultStagingLocationId":null,
                   "defaultQuarantineLocationId":null,"parents":[],
                   "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}}
                """.formatted(eventId, LocationUpdatedV1.EVENT_TYPE, version, LOCATION_ID, active);
    }

    @Test
    @DisplayName("keeps only the fields the scope replica needs from a much wider payload")
    void projectsTheNeededFields() {
        listener.onLocationEvent(locationUpdated("evt-1", 5, true));

        ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
        verify(extLocationReplicaRepository).save(captor.capture());
        ExtLocationReplica saved = captor.getValue();
        assertThat(saved.getLocationId()).isEqualTo(LOCATION_ID);
        assertThat(saved.getCode()).isEqualTo("SHOP-1");
        assertThat(saved.getName()).isEqualTo("Main Shop");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getAggregateVersion()).isEqualTo(5L);
        assertThat(saved.getSyncedAt()).isEqualTo(NOW);
        // Sets are materialised by the hierarchy service after the row lands, never here.
        assertThat(saved.getFinancialAncestorIds()).isEmpty();
        assertThat(saved.getOtherAncestorIds()).isEmpty();
        verify(locationHierarchyService).recomputeAncestors(LOCATION_ID);

        ArgumentCaptor<ProcessedEvent> dedup = ArgumentCaptor.forClass(ProcessedEvent.class);
        verify(processedEventRepository).save(dedup.capture());
        assertThat(dedup.getValue().getEventId()).isEqualTo("evt-1");
        assertThat(dedup.getValue().getOwner()).isEqualTo(LocationEventsListener.OWNER);
    }

    @Test
    @DisplayName("carries a deactivation through rather than defaulting to active")
    void deactivationIsProjected() {
        listener.onLocationEvent(locationUpdated("evt-2", 6, false));

        ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
        verify(extLocationReplicaRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    @DisplayName("ignores a snapshot strictly older than the replica but re-applies an equal one")
    void staleGuardIsStrictlyBelow() {
        when(extLocationReplicaRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(ExtLocationReplica.builder()
                        .locationId(LOCATION_ID)
                        .aggregateVersion(7)
                        .syncedAt(NOW)
                        .build()));

        listener.onLocationEvent(locationUpdated("evt-old", 6, true));
        verify(extLocationReplicaRepository, never()).save(any());
        verify(locationHierarchyService, never()).recomputeAncestors(any());
        // The stale fact is still recorded so the owner's manifest reconciles.
        verify(processedEventRepository).save(any());

        listener.onLocationEvent(locationUpdated("evt-equal", 7, true));
        verify(extLocationReplicaRepository).save(any());
        verify(locationHierarchyService).recomputeAncestors(LOCATION_ID);
    }

    @Test
    @DisplayName("removes the replica row and its parent edges when the location is deleted upstream")
    void deleteRemovesReplica() {
        listener.onLocationEvent("""
                {"eventId":"evt-3","eventType":"%s","payload":{"locationId":"%s"}}""".formatted(LocationDeletedV1.EVENT_TYPE, LOCATION_ID));

        verify(extLocationReplicaRepository).deleteById(LOCATION_ID);
        verify(extLocationParentReplicaRepository).deleteByChildId(LOCATION_ID);
        verify(locationHierarchyService, never()).recomputeAncestors(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("replaces the child's typed parent-edge set from the fact, then recomputes the subtree")
    void parentEdgesAreReplacedWholesaleThenRecomputed() {
        listener.onLocationEvent("""
                {"eventId":"evt-4","eventType":"%s","aggregateVersion":6,
                 "payload":{"locationId":"%s","name":"Main Shop","active":true,
                   "parents":[{"parentId":"%s","parentType":"PHYSICAL"}]}}
                """.formatted(LocationUpdatedV1.EVENT_TYPE, LOCATION_ID, PARENT_ID));

        verify(extLocationParentReplicaRepository).deleteByChildId(LOCATION_ID);
        ArgumentCaptor<ExtLocationParentReplica> captor = ArgumentCaptor.forClass(ExtLocationParentReplica.class);
        verify(extLocationParentReplicaRepository).save(captor.capture());
        assertThat(captor.getValue().getChildId()).isEqualTo(LOCATION_ID);
        assertThat(captor.getValue().getParentId()).isEqualTo(PARENT_ID);
        assertThat(captor.getValue().getParentType()).isEqualTo("PHYSICAL");
        // Edges changed, so the scope ancestor sets of this node and its subtree are rebuilt
        // after the edge replacement (ADR-0061 §2).
        InOrder inOrder = Mockito.inOrder(extLocationParentReplicaRepository, locationHierarchyService);
        inOrder.verify(extLocationParentReplicaRepository).save(any());
        inOrder.verify(locationHierarchyService).recomputeAncestors(LOCATION_ID);
    }

    @Test
    @DisplayName("a parent the replica has not seen is stored as an edge — ingestion never fails closed")
    void unknownParentIsStoredNotRejected() {
        listener.onLocationEvent("""
                {"eventId":"evt-4b","eventType":"%s","aggregateVersion":6,
                 "payload":{"locationId":"%s","name":"Main Shop","active":true,
                   "parents":[{"parentId":"%s","parentType":"FINANCIAL"}]}}
                """.formatted(LocationUpdatedV1.EVENT_TYPE, LOCATION_ID, PARENT_ID));

        verify(extLocationReplicaRepository).save(any());
        verify(extLocationParentReplicaRepository).save(any());
        verify(locationHierarchyService).recomputeAncestors(LOCATION_ID);
    }

    @Test
    @DisplayName("a fact without the parents field leaves existing edges untouched but still recomputes")
    void nullParentsLeaveEdgesUntouched() {
        listener.onLocationEvent("""
                {"eventId":"evt-5","eventType":"%s","aggregateVersion":6,
                 "payload":{"locationId":"%s","name":"Main Shop","active":true}}
                """.formatted(LocationUpdatedV1.EVENT_TYPE, LOCATION_ID));

        verify(extLocationParentReplicaRepository, never()).deleteByChildId(any());
        verify(extLocationParentReplicaRepository, never()).save(any());
        verify(locationHierarchyService).recomputeAncestors(LOCATION_ID);
    }

    @Test
    @DisplayName("records a dedup row for a storage-location fact it does not consume")
    void ignoredTypeStillRecorded() {
        listener.onLocationEvent("""
                {"eventId":"evt-6","eventType":"%s","payload":{"storageLocationId":"%s"}}""".formatted(StorageLocationUpdatedV1.EVENT_TYPE, LOCATION_ID));

        verify(extLocationReplicaRepository, never()).save(any());
        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("no-ops on a replayed eventId")
    void replayIsNoOp() {
        when(processedEventRepository.existsById("evt-1")).thenReturn(true);

        listener.onLocationEvent(locationUpdated("evt-1", 5, true));

        verify(extLocationReplicaRepository, never()).save(any());
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("rethrows a transient database error but swallows a malformed payload")
    void transientRethrownMalformedSwallowed() {
        when(extLocationReplicaRepository.findById(any())).thenThrow(new QueryTimeoutException("lock wait"));
        assertThatThrownBy(() -> listener.onLocationEvent(locationUpdated("evt-7", 5, true)))
                .isInstanceOf(QueryTimeoutException.class);
        verify(processedEventRepository, never()).save(any());

        Mockito.reset(extLocationReplicaRepository);
        listener.onLocationEvent("""
                {"eventId":"evt-8","eventType":"%s","aggregateVersion":1,
                 "payload":{"locationId":"not-a-uuid","name":"x","active":true}}
                """.formatted(LocationUpdatedV1.EVENT_TYPE));
        verify(extLocationReplicaRepository, never()).save(any());
        verify(locationHierarchyService, never()).recomputeAncestors(any());
        verify(processedEventRepository).save(any());
    }
}
