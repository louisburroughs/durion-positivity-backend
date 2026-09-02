package com.positivity.people.internal.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.domainevents.location.LocationDeletedV1;
import com.positivity.domainevents.location.LocationUpdatedV1;
import com.positivity.domainevents.workorder.JobTimeRecordedV1;
import com.positivity.people.internal.entity.ExtJobTimeReplica;
import com.positivity.people.internal.entity.ExtLocationParentReplica;
import com.positivity.people.internal.entity.ExtLocationReplica;
import com.positivity.people.internal.entity.ProcessedEvent;
import com.positivity.people.internal.repository.ExtJobTimeReplicaRepository;
import com.positivity.people.internal.repository.ExtLocationParentReplicaRepository;
import com.positivity.people.internal.repository.ExtLocationReplicaRepository;
import com.positivity.people.internal.repository.ProcessedEventRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.QueryTimeoutException;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for the location and workorder replica listeners in pos-people
 * (ADR-0044 §6, issues #892 and #875).
 *
 * <p>
 * These two look alike but differ on one point that is easy to "fix" wrongly:
 * <b>the location listener records a {@code processed_events} row even for event
 * types it ignores, and the workorder listener does not.</b> That asymmetry is
 * correct. Location has a reconciliation manifest listener
 * ({@link LocationManifestListener}) whose count must match the owner's, which
 * counts every fact in the window — so a skipped row would read as drift.
 * Workorder has no manifest listener in this module, so there is no count to
 * keep aligned and an early return costs nothing. Aligning them without moving
 * the manifest listeners would break one side or the other.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("pos-people location and workorder replica listeners")
class LocationAndWorkorderEventsListenerTest {

    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b1");
    private static final UUID LABOR_ENTRY_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID WORKORDER_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b3");
    private static final UUID TECHNICIAN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000b4");
    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private ProcessedEventRepository processedEventRepository;

    @Mock
    private ExtLocationReplicaRepository extLocationReplicaRepository;

    @Mock
    private ExtLocationParentReplicaRepository extLocationParentReplicaRepository;

    @Mock
    private ExtJobTimeReplicaRepository extJobTimeReplicaRepository;

    private LocationEventsListener locationListener;
    private WorkorderEventsListener workorderListener;

    @BeforeEach
    void setUp() {
        locationListener = new LocationEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                extLocationReplicaRepository,
                extLocationParentReplicaRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));
        workorderListener = new WorkorderEventsListener(
                clock,
                objectMapper,
                processedEventRepository,
                extJobTimeReplicaRepository,
                org.mockito.Mockito.mock(ObjectProvider.class));
        when(processedEventRepository.existsById(any())).thenReturn(false);
        when(extLocationReplicaRepository.findById(any())).thenReturn(Optional.empty());
    }

    @Nested
    @DisplayName("LocationEventsListener")
    class Locations {

        private String locationUpdated(String eventId, long version, boolean active) {
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
        @DisplayName("keeps only the fields this module needs from a much wider payload")
        void projectsTheNeededFields() {
            locationListener.onLocationEvent(locationUpdated("evt-1", 4, true));

            ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
            verify(extLocationReplicaRepository).save(captor.capture());
            ExtLocationReplica saved = captor.getValue();
            assertThat(saved.getLocationId()).isEqualTo(LOCATION_ID);
            assertThat(saved.getName()).isEqualTo("Main Shop");
            assertThat(saved.getStatus()).isEqualTo("OPEN");
            assertThat(saved.isActive()).isTrue();
            assertThat(saved.getAggregateVersion()).isEqualTo(4);
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("carries a deactivation through rather than defaulting to active")
        void deactivationIsReplicated() {
            locationListener.onLocationEvent(locationUpdated("evt-1", 4, false));

            ArgumentCaptor<ExtLocationReplica> captor = ArgumentCaptor.forClass(ExtLocationReplica.class);
            verify(extLocationReplicaRepository).save(captor.capture());
            assertThat(captor.getValue().isActive()).isFalse();
        }

        @Test
        @DisplayName("ignores a snapshot strictly older than the replica but re-applies an equal one")
        void staleGuardIsStrict() {
            when(extLocationReplicaRepository.findById(LOCATION_ID))
                    .thenReturn(Optional.of(ExtLocationReplica.builder()
                            .locationId(LOCATION_ID)
                            .aggregateVersion(5)
                            .build()));

            locationListener.onLocationEvent(locationUpdated("evt-1", 4, true));
            verify(extLocationReplicaRepository, never()).save(any());

            // Equal versions re-apply: the producer's version is an emission-timestamp hint, and
            // the payload is a full snapshot, so re-applying is cheaper than risking a lost update.
            locationListener.onLocationEvent(locationUpdated("evt-2", 5, true));
            verify(extLocationReplicaRepository).save(any());
        }

        @Test
        @DisplayName("removes the replica row and its parent edges when the location is deleted upstream")
        void deleteRemovesReplica() {
            locationListener.onLocationEvent("""
                    {"eventId":"evt-3","eventType":"%s","payload":{"locationId":"%s"}}""".formatted(LocationDeletedV1.EVENT_TYPE, LOCATION_ID));

            verify(extLocationReplicaRepository).deleteById(LOCATION_ID);
            verify(extLocationParentReplicaRepository).deleteByChildId(LOCATION_ID);
            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("replaces the child's typed parent-edge set from the fact (#1636)")
        void parentEdgesAreReplacedWholesale() {
            UUID parentId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
            locationListener.onLocationEvent("""
                    {"eventId":"evt-4","eventType":"%s","aggregateVersion":6,
                     "payload":{"locationId":"%s","name":"Main Shop","active":true,
                       "parents":[{"parentId":"%s","parentType":"PHYSICAL"}]}}
                    """.formatted(LocationUpdatedV1.EVENT_TYPE, LOCATION_ID, parentId));

            verify(extLocationParentReplicaRepository).deleteByChildId(LOCATION_ID);
            ArgumentCaptor<ExtLocationParentReplica> captor = ArgumentCaptor.forClass(ExtLocationParentReplica.class);
            verify(extLocationParentReplicaRepository).save(captor.capture());
            assertThat(captor.getValue().getChildId()).isEqualTo(LOCATION_ID);
            assertThat(captor.getValue().getParentId()).isEqualTo(parentId);
            assertThat(captor.getValue().getParentType()).isEqualTo("PHYSICAL");
        }

        @Test
        @DisplayName("a fact without the parents field leaves existing edges untouched")
        void nullParentsLeaveEdgesUntouched() {
            locationListener.onLocationEvent("""
                    {"eventId":"evt-5","eventType":"%s","aggregateVersion":6,
                     "payload":{"locationId":"%s","name":"Main Shop","active":true}}
                    """.formatted(LocationUpdatedV1.EVENT_TYPE, LOCATION_ID));

            verify(extLocationParentReplicaRepository, never()).deleteByChildId(any());
            verify(extLocationParentReplicaRepository, never()).save(any());
        }

        @Test
        @DisplayName("records a dedup row for an ignored type, because a manifest listener counts it")
        void ignoredTypeIsStillRecorded() {
            locationListener.onLocationEvent("""
                    {"eventId":"evt-4","eventType":"location.storage-location.updated","payload":{}}""");

            // LocationManifestListener compares against the owner's count of every fact in the
            // window, so omitting this row would register as drift.
            ArgumentCaptor<ProcessedEvent> captor = ArgumentCaptor.forClass(ProcessedEvent.class);
            verify(processedEventRepository).save(captor.capture());
            assertThat(captor.getValue().getOwner()).isEqualTo("location");
            verify(extLocationReplicaRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips unparsable messages and envelopes with no eventId")
        void malformedEnvelopesAreSkipped() {
            locationListener.onLocationEvent("{not json");
            locationListener.onLocationEvent(locationUpdated("", 1, true));

            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("no-ops on a replayed eventId")
        void replayIsIgnored() {
            when(processedEventRepository.existsById("evt-1")).thenReturn(true);

            locationListener.onLocationEvent(locationUpdated("evt-1", 4, true));

            verify(extLocationReplicaRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("rethrows a transient database error but swallows a malformed payload")
        void transientVersusMalformed() {
            when(extLocationReplicaRepository.findById(LOCATION_ID)).thenThrow(new QueryTimeoutException("lock wait"));

            assertThatThrownBy(() -> locationListener.onLocationEvent(locationUpdated("evt-1", 4, true)))
                    .isInstanceOf(QueryTimeoutException.class);
            verify(processedEventRepository, never()).save(any());

            locationListener.onLocationEvent("""
                    {"eventId":"evt-5","eventType":"%s","aggregateVersion":1,
                     "payload":{"locationId":"not-a-uuid"}}""".formatted(LocationUpdatedV1.EVENT_TYPE));

            // A poison message must not wedge the partition, and it still counts toward the window.
            verify(processedEventRepository).save(any());
        }
    }

    @Nested
    @DisplayName("WorkorderEventsListener")
    class Workorders {

        private String jobTime(String eventId, int minutes) {
            return """
                    {"eventId":"%s","eventType":"%s","aggregateVersion":1,
                     "payload":{"laborEntryId":"%s","workOrderId":"%s","technicianId":"%s",
                       "locationId":"%s","endAtUtc":"2026-08-11T08:30:00Z","minutes":%d}}
                    """.formatted(
                            eventId,
                            JobTimeRecordedV1.EVENT_TYPE,
                            LABOR_ENTRY_ID,
                            WORKORDER_ID,
                            TECHNICIAN_ID,
                            LOCATION_ID,
                            minutes);
        }

        @Test
        @DisplayName("replicates the labor entry that timesheet reporting reads")
        void replicatesJobTime() {
            workorderListener.onWorkorderEvent(jobTime("evt-1", 90));

            ArgumentCaptor<ExtJobTimeReplica> captor = ArgumentCaptor.forClass(ExtJobTimeReplica.class);
            verify(extJobTimeReplicaRepository).save(captor.capture());
            ExtJobTimeReplica saved = captor.getValue();
            assertThat(saved.getLaborEntryId()).isEqualTo(LABOR_ENTRY_ID);
            assertThat(saved.getWorkOrderId()).isEqualTo(WORKORDER_ID);
            assertThat(saved.getTechnicianId()).isEqualTo(TECHNICIAN_ID);
            assertThat(saved.getLocationId()).isEqualTo(LOCATION_ID);
            assertThat(saved.getMinutes()).isEqualTo(90);
            assertThat(saved.getEndAtUtc()).isEqualTo(Instant.parse("2026-08-11T08:30:00Z"));
            assertThat(saved.getUpdatedAt()).isEqualTo(NOW);
            verify(processedEventRepository).save(any());
        }

        @Test
        @DisplayName("returns without a dedup row for a type it does not consume")
        void ignoredTypeIsNotRecorded() {
            workorderListener.onWorkorderEvent("""
                    {"eventId":"evt-2","eventType":"workorder.workorder.updated","payload":{}}""");

            // Unlike the location listener: this module runs no workorder manifest listener, so
            // there is no count to keep aligned and the early return costs nothing.
            verify(processedEventRepository, never()).save(any());
            verify(extJobTimeReplicaRepository, never()).save(any());
        }

        @Test
        @DisplayName("skips unparsable messages, missing eventIds, and replays")
        void deliveryGuards() {
            workorderListener.onWorkorderEvent("{not json");
            workorderListener.onWorkorderEvent(jobTime("", 90));
            when(processedEventRepository.existsById("evt-1")).thenReturn(true);
            workorderListener.onWorkorderEvent(jobTime("evt-1", 90));

            verify(extJobTimeReplicaRepository, never()).save(any());
            verify(processedEventRepository, never()).save(any());
        }

        @Test
        @DisplayName("rethrows a transient database error but swallows a malformed payload")
        void transientVersusMalformed() {
            when(extJobTimeReplicaRepository.save(any())).thenThrow(new QueryTimeoutException("lock wait"));

            assertThatThrownBy(() -> workorderListener.onWorkorderEvent(jobTime("evt-1", 90)))
                    .isInstanceOf(QueryTimeoutException.class);
            verify(processedEventRepository, never()).save(any());

            workorderListener.onWorkorderEvent("""
                    {"eventId":"evt-3","eventType":"%s","payload":{"laborEntryId":"not-a-uuid"}}""".formatted(JobTimeRecordedV1.EVENT_TYPE));

            verify(processedEventRepository).save(any());
        }
    }
}
