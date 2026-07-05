package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.client.LocationRosterClient;
import com.positivity.inventory.internal.client.LocationRosterClient.LocationRosterEntry;
import com.positivity.inventory.internal.dto.sync.LocationSyncRunResponse;
import com.positivity.inventory.internal.dto.sync.SyncLogResponse;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.entity.LocationSyncLogEntity;
import com.positivity.inventory.internal.enums.LocationSyncLogScope;
import com.positivity.inventory.internal.enums.LocationSyncOutcome;
import com.positivity.inventory.internal.exception.LocationServiceUnavailableException;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.LocationSyncLogRepository;
import com.positivity.inventory.internal.service.LocationSyncServiceImpl;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for LocationSyncServiceImpl (CAP-214 #40).
 *
 * <p>
 * Covers idempotent upsert semantics (create / update / unchanged),
 * deactivation handling, invalid roster records, idempotency-key replay,
 * roster fetch failure auditing, and sync-log retrieval.
 */
@ExtendWith(MockitoExtension.class)
class LocationSyncServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-05T12:00:00Z"), ZoneOffset.UTC);
    private static final String USER = "sync-user";

    @Mock
    private LocationRosterClient locationRosterClient;

    @Mock
    private LocationRefRepository locationRefRepository;

    @Mock
    private LocationSyncLogRepository locationSyncLogRepository;

    private LocationSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LocationSyncServiceImpl(
                locationRosterClient, locationRefRepository, locationSyncLogRepository, FIXED_CLOCK);
    }

    private static LocationRosterEntry entry(UUID id, String name, String status) {
        return new LocationRosterEntry(
                id, name, "LOC-1", status, "HR-1", "America/New_York", Instant.parse("2026-07-01T00:00:00Z"));
    }

    // ─── triggerSync ───────────────────────────────────────────────────────────

    @Test
    void triggerSync_newLocation_createsRefAndLogsOkRun() {
        UUID locationId = UUID.randomUUID();
        when(locationRosterClient.fetchRoster()).thenReturn(List.of(entry(locationId, "Shop A", "ACTIVE")));
        when(locationRefRepository.findByLocationId(locationId)).thenReturn(Optional.empty());
        when(locationSyncLogRepository.save(any(LocationSyncLogEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LocationSyncRunResponse response = service.triggerSync(USER, null, "corr-1");

        assertThat(response.getOutcome()).isEqualTo("OK");
        assertThat(response.getLocationsProcessed()).isEqualTo(1);
        assertThat(response.getLocationsCreated()).isEqualTo(1);
        assertThat(response.getLocationsFailed()).isZero();
        assertThat(response.getSyncRunId()).isNotNull();
        assertThat(response.getCorrelationId()).isEqualTo("corr-1");

        ArgumentCaptor<LocationRefEntity> refCaptor = ArgumentCaptor.forClass(LocationRefEntity.class);
        verify(locationRefRepository).save(refCaptor.capture());
        LocationRefEntity saved = refCaptor.getValue();
        assertThat(saved.getLocationId()).isEqualTo(locationId);
        assertThat(saved.getName()).isEqualTo("Shop A");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.getTimezone()).isEqualTo("America/New_York");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getDeactivatedAt()).isNull();
    }

    @Test
    void triggerSync_sameRosterTwice_isIdempotentNoOp() {
        UUID locationId = UUID.randomUUID();
        LocationRosterEntry rosterEntry = entry(locationId, "Shop A", "ACTIVE");
        LocationRefEntity existing = LocationRefEntity.builder()
                .locationId(locationId)
                .name("Shop A")
                .code("LOC-1")
                .status("ACTIVE")
                .timezone("America/New_York")
                .hrLocationId("HR-1")
                .active(true)
                .sourceUpdatedAt(rosterEntry.updatedAt())
                .build();
        when(locationRosterClient.fetchRoster()).thenReturn(List.of(rosterEntry));
        when(locationRefRepository.findByLocationId(locationId)).thenReturn(Optional.of(existing));
        when(locationSyncLogRepository.save(any(LocationSyncLogEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LocationSyncRunResponse response = service.triggerSync(USER, null, null);

        assertThat(response.getOutcome()).isEqualTo("OK");
        assertThat(response.getLocationsUnchanged()).isEqualTo(1);
        assertThat(response.getLocationsCreated()).isZero();
        assertThat(response.getLocationsUpdated()).isZero();
        verify(locationRefRepository, never()).save(any());
    }

    @Test
    void triggerSync_deactivatedInRoster_marksRefInactiveWithTimestamp() {
        UUID locationId = UUID.randomUUID();
        LocationRosterEntry rosterEntry = entry(locationId, "Shop A", "INACTIVE");
        LocationRefEntity existing = LocationRefEntity.builder()
                .locationId(locationId)
                .name("Shop A")
                .code("LOC-1")
                .status("ACTIVE")
                .hrLocationId("HR-1")
                .timezone("America/New_York")
                .active(true)
                .build();
        when(locationRosterClient.fetchRoster()).thenReturn(List.of(rosterEntry));
        when(locationRefRepository.findByLocationId(locationId)).thenReturn(Optional.of(existing));
        when(locationSyncLogRepository.save(any(LocationSyncLogEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LocationSyncRunResponse response = service.triggerSync(USER, null, null);

        assertThat(response.getLocationsUpdated()).isEqualTo(1);
        ArgumentCaptor<LocationRefEntity> refCaptor = ArgumentCaptor.forClass(LocationRefEntity.class);
        verify(locationRefRepository).save(refCaptor.capture());
        LocationRefEntity saved = refCaptor.getValue();
        assertThat(saved.isActive()).isFalse();
        assertThat(saved.getStatus()).isEqualTo("INACTIVE");
        assertThat(saved.getDeactivatedAt()).isEqualTo(FIXED_CLOCK.instant());
    }

    @Test
    void triggerSync_invalidRosterRecord_logsInvalidPayloadAndPartialRun() {
        UUID goodId = UUID.randomUUID();
        LocationRosterEntry invalid = new LocationRosterEntry(null, "No Id", null, "ACTIVE", null, null, null);
        when(locationRosterClient.fetchRoster()).thenReturn(List.of(invalid, entry(goodId, "Shop A", "ACTIVE")));
        when(locationRefRepository.findByLocationId(goodId)).thenReturn(Optional.empty());
        when(locationSyncLogRepository.save(any(LocationSyncLogEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LocationSyncRunResponse response = service.triggerSync(USER, null, null);

        assertThat(response.getOutcome()).isEqualTo("PARTIAL");
        assertThat(response.getLocationsFailed()).isEqualTo(1);
        assertThat(response.getLocationsCreated()).isEqualTo(1);

        ArgumentCaptor<LocationSyncLogEntity> logCaptor = ArgumentCaptor.forClass(LocationSyncLogEntity.class);
        verify(locationSyncLogRepository, org.mockito.Mockito.times(2)).save(logCaptor.capture());
        LocationSyncLogEntity recordLog = logCaptor.getAllValues().get(0);
        assertThat(recordLog.getScope()).isEqualTo(LocationSyncLogScope.RECORD);
        assertThat(recordLog.getOutcome()).isEqualTo(LocationSyncOutcome.INVALID_PAYLOAD);
        LocationSyncLogEntity runLog = logCaptor.getAllValues().get(1);
        assertThat(runLog.getScope()).isEqualTo(LocationSyncLogScope.RUN);
        assertThat(runLog.getSyncRunId()).isEqualTo(recordLog.getSyncRunId());
    }

    @Test
    void triggerSync_replayedIdempotencyKey_returnsExistingRunWithoutSyncing() {
        UUID syncRunId = UUID.randomUUID();
        LocationSyncLogEntity existingRun = LocationSyncLogEntity.builder()
                .syncRunId(syncRunId)
                .scope(LocationSyncLogScope.RUN)
                .outcome(LocationSyncOutcome.OK)
                .idempotencyKey("key-1")
                .locationsProcessed(5)
                .locationsCreated(2)
                .locationsUpdated(1)
                .locationsUnchanged(2)
                .locationsFailed(0)
                .build();
        when(locationSyncLogRepository.findFirstByIdempotencyKeyAndScope("key-1", LocationSyncLogScope.RUN))
                .thenReturn(Optional.of(existingRun));

        LocationSyncRunResponse response = service.triggerSync(USER, "key-1", null);

        assertThat(response.getSyncRunId()).isEqualTo(syncRunId);
        assertThat(response.getLocationsProcessed()).isEqualTo(5);
        verify(locationRosterClient, never()).fetchRoster();
        verify(locationSyncLogRepository, never()).save(any());
    }

    @Test
    void triggerSync_rosterFetchFails_recordsFailedRunAndRethrows() {
        when(locationRosterClient.fetchRoster())
                .thenThrow(new LocationServiceUnavailableException("location down", null));
        when(locationSyncLogRepository.save(any(LocationSyncLogEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.triggerSync(USER, null, "corr-9"))
                .isInstanceOf(LocationServiceUnavailableException.class);

        ArgumentCaptor<LocationSyncLogEntity> logCaptor = ArgumentCaptor.forClass(LocationSyncLogEntity.class);
        verify(locationSyncLogRepository).save(logCaptor.capture());
        LocationSyncLogEntity failedRun = logCaptor.getValue();
        assertThat(failedRun.getScope()).isEqualTo(LocationSyncLogScope.RUN);
        assertThat(failedRun.getOutcome()).isEqualTo(LocationSyncOutcome.FAILED);
        assertThat(failedRun.getErrorMessage()).contains("location down");
        assertThat(failedRun.getCorrelationId()).isEqualTo("corr-9");
    }

    @Test
    void triggerSync_missingHrFields_doesNotOverwriteExistingValuesWithNull() {
        UUID locationId = UUID.randomUUID();
        LocationRosterEntry sparse =
                new LocationRosterEntry(locationId, "Shop A Renamed", null, null, null, null, null);
        LocationRefEntity existing = LocationRefEntity.builder()
                .locationId(locationId)
                .name("Shop A")
                .code("LOC-1")
                .status("ACTIVE")
                .hrLocationId("HR-1")
                .timezone("America/New_York")
                .active(true)
                .build();
        when(locationRosterClient.fetchRoster()).thenReturn(List.of(sparse));
        when(locationRefRepository.findByLocationId(locationId)).thenReturn(Optional.of(existing));
        when(locationSyncLogRepository.save(any(LocationSyncLogEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.triggerSync(USER, null, null);

        ArgumentCaptor<LocationRefEntity> refCaptor = ArgumentCaptor.forClass(LocationRefEntity.class);
        verify(locationRefRepository).save(refCaptor.capture());
        LocationRefEntity saved = refCaptor.getValue();
        assertThat(saved.getName()).isEqualTo("Shop A Renamed");
        assertThat(saved.getCode()).isEqualTo("LOC-1");
        assertThat(saved.getHrLocationId()).isEqualTo("HR-1");
        assertThat(saved.getTimezone()).isEqualTo("America/New_York");
        assertThat(saved.getStatus()).isEqualTo("ACTIVE");
        assertThat(saved.isActive()).isTrue();
    }

    // ─── listSyncLogs / getSyncLog ─────────────────────────────────────────────

    @Test
    void listSyncLogs_mapsEntitiesNewestFirst() {
        LocationSyncLogEntity log = LocationSyncLogEntity.builder()
                .syncLogId(UUID.randomUUID())
                .syncRunId(UUID.randomUUID())
                .scope(LocationSyncLogScope.RUN)
                .outcome(LocationSyncOutcome.OK)
                .correlationId("corr-1")
                .createdAt(FIXED_CLOCK.instant())
                .build();
        when(locationSyncLogRepository.findByOptionalOutcome(eq(LocationSyncOutcome.OK), any()))
                .thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(log)));

        List<SyncLogResponse> responses = service.listSyncLogs(LocationSyncOutcome.OK, 0, 20);

        assertThat(responses).hasSize(1);
        SyncLogResponse response = responses.get(0);
        assertThat(response.getSyncLogId()).isEqualTo(log.getSyncLogId());
        assertThat(response.getSyncRunId()).isEqualTo(log.getSyncRunId());
        assertThat(response.getOutcome()).isEqualTo("OK");
        assertThat(response.getCorrelationId()).isEqualTo("corr-1");
        assertThat(response.getCreatedAt()).isEqualTo(FIXED_CLOCK.instant());
    }

    @Test
    void getSyncLog_unknownId_throwsNotFound() {
        UUID syncLogId = UUID.randomUUID();
        when(locationSyncLogRepository.findById(syncLogId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getSyncLog(syncLogId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining(syncLogId.toString());
    }
}
