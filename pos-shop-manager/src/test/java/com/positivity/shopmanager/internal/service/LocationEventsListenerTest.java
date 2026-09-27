package com.positivity.shopmanager.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.BaySpecialtyMapUpdatedV1;
import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.location.StorageLocationUpdatedV1;
import com.positivity.shopmanager.internal.entity.ExtBayReplica;
import com.positivity.shopmanager.internal.entity.ExtBaySpecialtyMapReplica;
import com.positivity.shopmanager.internal.entity.ExtBayTypeReplica;
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
import org.jspecify.annotations.Nullable;
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
import org.springframework.transaction.PlatformTransactionManager;
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
    private com.positivity.shopmanager.internal.repository.ExtBaySpecialtyMapReplicaRepository
            extBaySpecialtyMapReplicaRepository;

    @Mock
    private com.positivity.shopmanager.internal.repository.ExtBayTypeReplicaRepository extBayTypeReplicaRepository;

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
                extBaySpecialtyMapReplicaRepository,
                extBayTypeReplicaRepository,
                locationHierarchyService,
                Mockito.mock(ObjectProvider.class),
                Mockito.mock(PlatformTransactionManager.class));
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

    /** Same shape as {@link #locationUpdated}, plus the #2023 hours/closures/buffer fields. */
    private static String locationUpdatedWithHours(
            String eventId, long version, @Nullable String operatingHoursJson, @Nullable String holidayClosuresJson) {
        String operatingHours = operatingHoursJson == null ? "null" : operatingHoursJson;
        String holidayClosures = holidayClosuresJson == null ? "null" : holidayClosuresJson;
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,
                 "payload":{"locationId":"%s","name":"Main Shop","code":"SHOP-1","status":"OPEN",
                   "active":true,"locationType":"SHOP","hrLocationId":null,"timezone":"America/Chicago",
                   "addressLine1":"1 Main St","addressLine2":null,"city":"Austin","region":"TX",
                   "postalCode":"78701","country":"US","defaultStagingLocationId":null,
                   "defaultQuarantineLocationId":null,"parents":[],
                   "operatingHours":%s,"holidayClosures":%s,
                   "checkInBufferMinutes":15,"cleanupBufferMinutes":10,
                   "createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-08-01T00:00:00Z"}}
                """.formatted(
                        eventId, LocationUpdatedV1.EVENT_TYPE, version, LOCATION_ID, operatingHours, holidayClosures);
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
    @DisplayName("#2023: hours, closures and both buffers land on ext_location; timezone lands")
    void operatingHoursClosuresAndBuffersLand() {
        String hours = """
                [{"dayOfWeek":"MONDAY","openTime":"08:00:00","closeTime":"17:00:00"}]""";
        String closures = """
                [{"date":"2026-12-25","reason":"Christmas"}]""";

        listener.onLocationEvent(locationUpdatedWithHours("evt-hours", 5, hours, closures));

        ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
        verify(extLocationReplicaRepository).save(captor.capture());
        ExtLocationReplica saved = captor.getValue();
        assertThat(saved.getTimezone()).isEqualTo("America/Chicago");
        assertThat(saved.getOperatingHours())
                .contains("MONDAY")
                .contains("08:00:00")
                .contains("17:00:00");
        assertThat(saved.getHolidayClosures()).contains("2026-12-25").contains("Christmas");
        assertThat(saved.getCheckInBufferMinutes()).isEqualTo(15);
        assertThat(saved.getCleanupBufferMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("#2023 DECISION-LOCATION-004/005: null operatingHours stores null, "
            + "an empty list stores \"[]\", and the two stay distinguishable on read-back")
    void nullVersusEmptyOperatingHoursIsPreserved() {
        listener.onLocationEvent(locationUpdatedWithHours("evt-null", 5, null, null));
        listener.onLocationEvent(locationUpdatedWithHours("evt-empty", 6, "[]", "[]"));

        ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
        verify(extLocationReplicaRepository, Mockito.times(2)).save(captor.capture());
        ExtLocationReplica notConfigured = captor.getAllValues().get(0);
        ExtLocationReplica configuredClosed = captor.getAllValues().get(1);
        assertThat(notConfigured.getOperatingHours()).isNull();
        assertThat(notConfigured.getHolidayClosures()).isNull();
        assertThat(configuredClosed.getOperatingHours()).isEqualTo("[]");
        assertThat(configuredClosed.getHolidayClosures()).isEqualTo("[]");
    }

    @Test
    @DisplayName("a fact predating the #2023 hours/buffer fields does not corrupt the row's other columns")
    void factPredatingNewFieldsDoesNotCorruptOtherColumns() {
        listener.onLocationEvent(locationUpdated("evt-old-shape", 5, true));

        ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
        verify(extLocationReplicaRepository).save(captor.capture());
        ExtLocationReplica saved = captor.getValue();
        assertThat(saved.getCode()).isEqualTo("SHOP-1");
        assertThat(saved.getName()).isEqualTo("Main Shop");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getTimezone()).isEqualTo("America/Chicago");
        assertThat(saved.getOperatingHours()).isNull();
        assertThat(saved.getHolidayClosures()).isNull();
        assertThat(saved.getCheckInBufferMinutes()).isNull();
        assertThat(saved.getCleanupBufferMinutes()).isNull();
    }

    @Test
    @DisplayName("#2023 F2 - a fact with timezone/hours/closures/buffers entirely absent keeps "
            + "the already-replicated values, never erases them")
    void addedFieldsAbsentFromEnvelopeKeepExistingValues() {
        when(extLocationReplicaRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(ExtLocationReplica.builder()
                        .locationId(LOCATION_ID)
                        .aggregateVersion(5)
                        .timezone("America/Chicago")
                        .operatingHours(
                                "[{\"dayOfWeek\":\"MONDAY\",\"openTime\":\"08:00:00\",\"closeTime\":\"17:00:00\"}]")
                        .holidayClosures("[{\"date\":\"2026-12-25\",\"reason\":\"Christmas\"}]")
                        .checkInBufferMinutes(15)
                        .cleanupBufferMinutes(10)
                        .build()));

        // A pre-#2023 producer's shape: no timezone/operatingHours/holidayClosures/buffer keys at
        // all - a rolling deploy or a replay of a stored older event, not an explicit clear.
        listener.onLocationEvent("""
                {"eventId":"evt-absent","eventType":"%s","aggregateVersion":6,
                 "payload":{"locationId":"%s","name":"Main Shop","code":"SHOP-1","status":"OPEN",
                   "active":true,"parents":[]}}
                """.formatted(LocationUpdatedV1.EVENT_TYPE, LOCATION_ID));

        ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
        verify(extLocationReplicaRepository).save(captor.capture());
        ExtLocationReplica saved = captor.getValue();
        assertThat(saved.getTimezone()).isEqualTo("America/Chicago");
        assertThat(saved.getOperatingHours()).contains("MONDAY");
        assertThat(saved.getHolidayClosures()).contains("Christmas");
        assertThat(saved.getCheckInBufferMinutes()).isEqualTo(15);
        assertThat(saved.getCleanupBufferMinutes()).isEqualTo(10);
    }

    @Test
    @DisplayName("#2023 F2 - a fact with explicit null timezone/hours/closures/buffers clears the "
            + "already-replicated values")
    void addedFieldsExplicitNullClearExistingValues() {
        when(extLocationReplicaRepository.findById(LOCATION_ID))
                .thenReturn(Optional.of(ExtLocationReplica.builder()
                        .locationId(LOCATION_ID)
                        .aggregateVersion(5)
                        .timezone("America/Chicago")
                        .operatingHours(
                                "[{\"dayOfWeek\":\"MONDAY\",\"openTime\":\"08:00:00\",\"closeTime\":\"17:00:00\"}]")
                        .holidayClosures("[{\"date\":\"2026-12-25\",\"reason\":\"Christmas\"}]")
                        .checkInBufferMinutes(15)
                        .cleanupBufferMinutes(10)
                        .build()));

        // The owner explicitly reset every one of these to null - the fact says so in the raw
        // JSON, distinct from the fields being absent.
        listener.onLocationEvent("""
                {"eventId":"evt-explicit-null","eventType":"%s","aggregateVersion":6,
                 "payload":{"locationId":"%s","name":"Main Shop","code":"SHOP-1","status":"OPEN",
                   "active":true,"parents":[],"timezone":null,"operatingHours":null,
                   "holidayClosures":null,"checkInBufferMinutes":null,"cleanupBufferMinutes":null}}
                """.formatted(LocationUpdatedV1.EVENT_TYPE, LOCATION_ID));

        ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
        verify(extLocationReplicaRepository).save(captor.capture());
        ExtLocationReplica saved = captor.getValue();
        assertThat(saved.getTimezone()).isNull();
        assertThat(saved.getOperatingHours()).isNull();
        assertThat(saved.getHolidayClosures()).isNull();
        assertThat(saved.getCheckInBufferMinutes()).isNull();
        assertThat(saved.getCleanupBufferMinutes()).isNull();
    }

    @Test
    @DisplayName("#2023 F2 - a bay fact with bayType absent keeps the already-replicated value")
    void bayTypeAbsentFromEnvelopeKeepsExistingValue() {
        when(extBayReplicaRepository.findById(any()))
                .thenReturn(Optional.of(ExtBayReplica.builder()
                        .bayId(LOCATION_ID)
                        .locationId(LOCATION_ID)
                        .bayType("LIFT")
                        .aggregateVersion(1)
                        .build()));

        listener.onLocationEvent(
                """
                {"eventId":"evt-bay-absent","eventType":"%s","aggregateVersion":2,
                 "payload":{"bayId":"%s","locationId":"%s","name":"Front Bay 1","status":"ACTIVE"}}
                """.formatted(com.positivity.domainevents.location.BayUpdatedV1.EVENT_TYPE, LOCATION_ID, LOCATION_ID));

        ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
        verify(extBayReplicaRepository).save(captor.capture());
        assertThat(captor.getValue().getBayType()).isEqualTo("LIFT");
    }

    @Test
    @DisplayName("#2023 F2 - a bay fact with an explicit null bayType clears the already-replicated value")
    void bayTypeExplicitNullClearsExistingValue() {
        when(extBayReplicaRepository.findById(any()))
                .thenReturn(Optional.of(ExtBayReplica.builder()
                        .bayId(LOCATION_ID)
                        .locationId(LOCATION_ID)
                        .bayType("LIFT")
                        .aggregateVersion(1)
                        .build()));

        listener.onLocationEvent(
                """
                {"eventId":"evt-bay-null","eventType":"%s","aggregateVersion":2,
                 "payload":{"bayId":"%s","locationId":"%s","name":"Front Bay 1","bayType":null,
                   "status":"ACTIVE"}}
                """.formatted(com.positivity.domainevents.location.BayUpdatedV1.EVENT_TYPE, LOCATION_ID, LOCATION_ID));

        ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
        verify(extBayReplicaRepository).save(captor.capture());
        assertThat(captor.getValue().getBayType()).isNull();
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

    // -- #2261 DECISION-LOCATION-025: bay specialty map replica ---------------------------------

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000d1");

    private static String baySpecialtyMapUpdated(String eventId, long version) {
        return """
                {"eventId":"%s","eventType":"%s","aggregateVersion":%d,
                 "payload":{"tenantId":"%s","aggregateVersion":%d,
                   "entries":[
                     {"bayType":"ALIGNMENT","operationCodes":["ALIGN-4-WHEEL"],"acceptsGeneralWork":false},
                     {"bayType":"GENERAL_SERVICE","operationCodes":[],"acceptsGeneralWork":true},
                     {"bayType":"WASH_DETAIL","operationCodes":[],"acceptsGeneralWork":false}
                   ]}}
                """.formatted(eventId, BaySpecialtyMapUpdatedV1.EVENT_TYPE, version, TENANT_ID, version);
    }

    @Test
    @DisplayName("#2261: applies a full replace of the tenant's bay specialty map")
    void baySpecialtyMapAppliesFullReplace() {
        when(extBayTypeReplicaRepository.findFirstByOrderByBayTypeAsc()).thenReturn(Optional.empty());

        listener.onLocationEvent(baySpecialtyMapUpdated("evt-map-1", 1));

        verify(extBaySpecialtyMapReplicaRepository).deleteAll();
        verify(extBayTypeReplicaRepository).deleteAll();

        ArgumentCaptor<ExtBayTypeReplica> bayTypeCaptor = ArgumentCaptor.forClass(ExtBayTypeReplica.class);
        verify(extBayTypeReplicaRepository, Mockito.times(3)).save(bayTypeCaptor.capture());
        assertThat(bayTypeCaptor.getAllValues())
                .extracting(ExtBayTypeReplica::getBayType, ExtBayTypeReplica::isAcceptsGeneralWork)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("ALIGNMENT", false),
                        org.assertj.core.groups.Tuple.tuple("GENERAL_SERVICE", true),
                        org.assertj.core.groups.Tuple.tuple("WASH_DETAIL", false));
        assertThat(bayTypeCaptor.getAllValues())
                .allSatisfy(row -> assertThat(row.getAggregateVersion()).isEqualTo(1L));

        ArgumentCaptor<ExtBaySpecialtyMapReplica> opCaptor = ArgumentCaptor.forClass(ExtBaySpecialtyMapReplica.class);
        verify(extBaySpecialtyMapReplicaRepository).save(opCaptor.capture());
        assertThat(opCaptor.getValue().getBayType()).isEqualTo("ALIGNMENT");
        assertThat(opCaptor.getValue().getOperationCode()).isEqualTo("ALIGN-4-WHEEL");

        verify(processedEventRepository).save(any());
    }

    @Test
    @DisplayName("#2261: ignores a strictly older bay specialty map version but re-applies an equal one")
    void baySpecialtyMapStaleGuardIsStrictlyBelow() {
        when(extBayTypeReplicaRepository.findFirstByOrderByBayTypeAsc())
                .thenReturn(Optional.of(ExtBayTypeReplica.builder()
                        .bayType("ALIGNMENT")
                        .acceptsGeneralWork(false)
                        .aggregateVersion(7)
                        .build()));

        listener.onLocationEvent(baySpecialtyMapUpdated("evt-map-old", 6));
        verify(extBaySpecialtyMapReplicaRepository, never()).deleteAll();
        verify(extBayTypeReplicaRepository, never()).deleteAll();
        // The stale fact is still recorded so the owner's manifest reconciles.
        verify(processedEventRepository).save(any());

        listener.onLocationEvent(baySpecialtyMapUpdated("evt-map-equal", 7));
        verify(extBaySpecialtyMapReplicaRepository).deleteAll();
        verify(extBayTypeReplicaRepository).deleteAll();
    }

    @Test
    @DisplayName("#2261: is idempotent on a replayed bay specialty map eventId")
    void baySpecialtyMapReplayIsNoOp() {
        when(processedEventRepository.existsById("evt-map-1")).thenReturn(true);

        listener.onLocationEvent(baySpecialtyMapUpdated("evt-map-1", 1));

        verify(extBaySpecialtyMapReplicaRepository, never()).deleteAll();
        verify(extBayTypeReplicaRepository, never()).deleteAll();
        verify(processedEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("#2261: acceptsGeneralWork maps true/false from BayUpdatedV1")
    void bayAcceptsGeneralWorkIsMapped() {
        listener.onLocationEvent(
                """
                {"eventId":"evt-agw-1","eventType":"%s","aggregateVersion":1,
                 "payload":{"bayId":"%s","locationId":"%s","name":"Wash Bay","status":"ACTIVE",
                   "acceptsGeneralWork":false}}
                """.formatted(com.positivity.domainevents.location.BayUpdatedV1.EVENT_TYPE, LOCATION_ID, LOCATION_ID));

        ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
        verify(extBayReplicaRepository).save(captor.capture());
        assertThat(captor.getValue().isAcceptsGeneralWork()).isFalse();
    }

    @Test
    @DisplayName("#2261: acceptsGeneralWork absent from the fact keeps the already-replicated value")
    void bayAcceptsGeneralWorkAbsentKeepsExistingValue() {
        when(extBayReplicaRepository.findById(any()))
                .thenReturn(Optional.of(ExtBayReplica.builder()
                        .bayId(LOCATION_ID)
                        .locationId(LOCATION_ID)
                        .acceptsGeneralWork(false)
                        .aggregateVersion(1)
                        .build()));

        listener.onLocationEvent(
                """
                {"eventId":"evt-agw-2","eventType":"%s","aggregateVersion":2,
                 "payload":{"bayId":"%s","locationId":"%s","name":"Wash Bay","status":"ACTIVE"}}
                """.formatted(com.positivity.domainevents.location.BayUpdatedV1.EVENT_TYPE, LOCATION_ID, LOCATION_ID));

        ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
        verify(extBayReplicaRepository).save(captor.capture());
        // A pre-DECISION-LOCATION-025 producer's shape: no acceptsGeneralWork key at all, so the
        // already-replicated false is kept rather than defaulting back to true.
        assertThat(captor.getValue().isAcceptsGeneralWork()).isFalse();
    }

    @Test
    @DisplayName("#2261: acceptsGeneralWork absent on a brand-new bay row defaults to true")
    void bayAcceptsGeneralWorkAbsentOnNewRowDefaultsTrue() {
        listener.onLocationEvent(
                """
                {"eventId":"evt-agw-3","eventType":"%s","aggregateVersion":1,
                 "payload":{"bayId":"%s","locationId":"%s","name":"General Bay","status":"ACTIVE"}}
                """.formatted(com.positivity.domainevents.location.BayUpdatedV1.EVENT_TYPE, LOCATION_ID, LOCATION_ID));

        ArgumentCaptor<ExtBayReplica> captor = ArgumentCaptor.forClass(ExtBayReplica.class);
        verify(extBayReplicaRepository).save(captor.capture());
        assertThat(captor.getValue().isAcceptsGeneralWork()).isTrue();
    }
}
