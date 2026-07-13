package com.positivity.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.positivity.inventory.internal.dto.sync.LocationSyncRunResponse;
import com.positivity.inventory.internal.dto.sync.SyncLogResponse;
import com.positivity.inventory.internal.entity.LocationSyncLogEntity;
import com.positivity.inventory.internal.enums.LocationSyncLogScope;
import com.positivity.inventory.internal.enums.LocationSyncOutcome;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.LocationSyncLogRepository;
import com.positivity.inventory.internal.service.LocationSyncServiceImpl;
import java.time.Clock;
import java.time.Duration;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

/**
 * Unit tests for LocationSyncServiceImpl (CAP-214 #40, reworked for ADR-0044 #892).
 *
 * <p>The manual trigger now performs an administrative re-emit: it publishes a
 * {@code location.outbox.replay-requested} command and records a REQUESTED run; the
 * location-events consumer repairs the replica asynchronously.
 */
@ExtendWith(MockitoExtension.class)
class LocationSyncServiceImplTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-05T12:00:00Z"), ZoneOffset.UTC);
    private static final String USER = "sync-user";
    private static final String TOPIC = "location.commands.v1";

    @Mock
    private LocationSyncLogRepository locationSyncLogRepository;

    @SuppressWarnings("unchecked")
    private final ObjectProvider<KafkaTemplate<String, String>> templateProvider = mock(ObjectProvider.class);

    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafkaTemplate = mock(KafkaTemplate.class);

    private LocationSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new LocationSyncServiceImpl(
                locationSyncLogRepository, templateProvider, new ObjectMapper(), FIXED_CLOCK);
        ReflectionTestUtils.setField(service, "locationCommandsTopic", TOPIC);
        ReflectionTestUtils.setField(service, "replayLookback", Duration.ofDays(30));
    }

    @Test
    void triggerSync_publishesReplayCommandAndLogsRequestedRun() {
        when(templateProvider.getIfAvailable()).thenReturn(kafkaTemplate);
        when(locationSyncLogRepository.save(any(LocationSyncLogEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LocationSyncRunResponse response = service.triggerSync(USER, null, "corr-1");

        assertThat(response.getOutcome()).isEqualTo("REQUESTED");
        assertThat(response.getSyncRunId()).isNotNull();
        assertThat(response.getCorrelationId()).isEqualTo("corr-1");

        Instant expectedSince = FIXED_CLOCK.instant().minus(Duration.ofDays(30));
        ArgumentCaptor<String> commandCaptor = ArgumentCaptor.forClass(String.class);
        verify(kafkaTemplate).send(eq(TOPIC), eq(expectedSince.toString()), commandCaptor.capture());
        assertThat(commandCaptor.getValue())
                .contains("location.outbox.replay-requested")
                .contains(expectedSince.toString());

        ArgumentCaptor<LocationSyncLogEntity> logCaptor = ArgumentCaptor.forClass(LocationSyncLogEntity.class);
        verify(locationSyncLogRepository).save(logCaptor.capture());
        LocationSyncLogEntity runLog = logCaptor.getValue();
        assertThat(runLog.getScope()).isEqualTo(LocationSyncLogScope.RUN);
        assertThat(runLog.getOutcome()).isEqualTo(LocationSyncOutcome.REQUESTED);
        assertThat(runLog.getTriggeredBy()).isEqualTo(USER);
    }

    @Test
    void triggerSync_eventFeedDisabled_recordsFailedRun() {
        when(templateProvider.getIfAvailable()).thenReturn(null);
        when(locationSyncLogRepository.save(any(LocationSyncLogEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        LocationSyncRunResponse response = service.triggerSync(USER, null, null);

        assertThat(response.getOutcome()).isEqualTo("FAILED");
        ArgumentCaptor<LocationSyncLogEntity> logCaptor = ArgumentCaptor.forClass(LocationSyncLogEntity.class);
        verify(locationSyncLogRepository).save(logCaptor.capture());
        assertThat(logCaptor.getValue().getErrorMessage()).contains("disabled");
    }

    @Test
    void triggerSync_replayedIdempotencyKey_returnsExistingRunWithoutRequesting() {
        UUID syncRunId = UUID.randomUUID();
        LocationSyncLogEntity existingRun = LocationSyncLogEntity.builder()
                .syncRunId(syncRunId)
                .scope(LocationSyncLogScope.RUN)
                .outcome(LocationSyncOutcome.REQUESTED)
                .idempotencyKey("key-1")
                .locationsProcessed(0)
                .build();
        when(locationSyncLogRepository.findFirstByIdempotencyKeyAndScope("key-1", LocationSyncLogScope.RUN))
                .thenReturn(Optional.of(existingRun));

        LocationSyncRunResponse response = service.triggerSync(USER, "key-1", null);

        assertThat(response.getSyncRunId()).isEqualTo(syncRunId);
        assertThat(response.getOutcome()).isEqualTo("REQUESTED");
        verify(kafkaTemplate, never()).send(any(), any(), any());
        verify(locationSyncLogRepository, never()).save(any());
    }

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
