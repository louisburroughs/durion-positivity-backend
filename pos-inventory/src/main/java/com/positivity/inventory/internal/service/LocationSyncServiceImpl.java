package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.entity.LocationSyncLogEntity;
import com.positivity.inventory.internal.enums.LocationSyncLogScope;
import com.positivity.inventory.internal.enums.LocationSyncOutcome;
import com.positivity.inventory.internal.dto.sync.LocationSyncRunResponse;
import com.positivity.inventory.internal.dto.sync.SyncLogResponse;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.LocationSyncLogRepository;
import com.positivity.inventory.service.LocationSyncService;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * On-demand location re-sync trigger and the sync audit trail (CAP-214 #40, reworked for
 * ADR-0044 §6 #892). The {@code location_ref} roster is maintained continuously by
 * {@link LocationEventsListener}; a manual trigger now performs an administrative re-emit — it
 * publishes a {@code location.outbox.replay-requested} command so pos-location re-publishes its
 * outbox for the lookback window, and the event consumer idempotently repairs the replica. The
 * REST roster pull (retired {@code LocationRosterClient}) is gone.
 */
@Service
@RequiredArgsConstructor
public class LocationSyncServiceImpl implements LocationSyncService {

    private static final Logger log = LoggerFactory.getLogger(LocationSyncServiceImpl.class);
    private static final String REPLAY_COMMAND_TYPE = "location.outbox.replay-requested";

    private final LocationSyncLogRepository locationSyncLogRepository;
    private final ObjectProvider<KafkaTemplate<String, String>> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Value("${pos.inventory.kafka.location-commands-topic:location.commands.v1}")
    private String locationCommandsTopic;

    /** Window the owner is asked to re-emit; bounded by the owner's replay max-lookback. */
    @Value("${pos.inventory.location.replay-lookback:P30D}")
    private Duration replayLookback;

    @Override
    @NonNull
    @Transactional
    public LocationSyncRunResponse triggerSync(
            @NonNull String triggeredBy, @Nullable String idempotencyKey, @Nullable String correlationId) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<LocationSyncLogEntity> existing = locationSyncLogRepository.findFirstByIdempotencyKeyAndScope(
                    idempotencyKey, LocationSyncLogScope.RUN);
            if (existing.isPresent()) {
                return toRunResponse(existing.get());
            }
        }

        UUID syncRunId = UUIDv7Generator.generate();
        Instant startedAt = clock.instant();
        LocationSyncLogEntity.LocationSyncLogEntityBuilder runLog = LocationSyncLogEntity.builder()
                .syncRunId(syncRunId)
                .scope(LocationSyncLogScope.RUN)
                .correlationId(correlationId)
                .idempotencyKey(normalizeKey(idempotencyKey))
                .triggeredBy(triggeredBy)
                .locationsProcessed(0)
                .locationsCreated(0)
                .locationsUpdated(0)
                .locationsUnchanged(0)
                .locationsFailed(0)
                .startedAt(startedAt);

        KafkaTemplate<String, String> template = kafkaTemplate.getIfAvailable();
        if (template == null) {
            LocationSyncLogEntity saved = locationSyncLogRepository.save(runLog.outcome(LocationSyncOutcome.FAILED)
                    .errorMessage("Location event feed is disabled (pos.inventory.kafka.enabled=false);"
                            + " cannot request an owner re-emit")
                    .completedAt(clock.instant())
                    .build());
            return toRunResponse(saved);
        }

        Instant since = startedAt.minus(replayLookback);
        try {
            String command = objectMapper.writeValueAsString(
                    new ReplayCommand(REPLAY_COMMAND_TYPE, new ReplayCommand.Payload(since.toString(), null)));
            template.send(locationCommandsTopic, since.toString(), command);
        } catch (Exception ex) {
            LocationSyncLogEntity saved = locationSyncLogRepository.save(runLog.outcome(LocationSyncOutcome.FAILED)
                    .errorMessage(truncate(ex.getMessage(), 2000))
                    .completedAt(clock.instant())
                    .build());
            return toRunResponse(saved);
        }

        LocationSyncLogEntity saved = locationSyncLogRepository.save(runLog.outcome(LocationSyncOutcome.REQUESTED)
                .completedAt(clock.instant())
                .build());
        log.info(
                "Location re-emit {} requested since={} on {} by {}",
                syncRunId,
                since,
                locationCommandsTopic,
                triggeredBy);
        return toRunResponse(saved);
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public List<SyncLogResponse> listSyncLogs(@Nullable LocationSyncOutcome outcome, int page, int size) {
        return locationSyncLogRepository
                .findByOptionalOutcome(outcome, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")))
                .map(this::toLogResponse)
                .getContent();
    }

    @Override
    @NonNull
    @Transactional(readOnly = true)
    public SyncLogResponse getSyncLog(@NonNull UUID syncLogId) {
        return locationSyncLogRepository
                .findById(syncLogId)
                .map(this::toLogResponse)
                .orElseThrow(() -> new ResourceNotFoundException("Sync log", syncLogId.toString()));
    }

    private LocationSyncRunResponse toRunResponse(LocationSyncLogEntity runLog) {
        return LocationSyncRunResponse.builder()
                .syncRunId(runLog.getSyncRunId())
                .outcome(runLog.getOutcome().name())
                .locationsProcessed(zeroIfNull(runLog.getLocationsProcessed()))
                .locationsCreated(zeroIfNull(runLog.getLocationsCreated()))
                .locationsUpdated(zeroIfNull(runLog.getLocationsUpdated()))
                .locationsUnchanged(zeroIfNull(runLog.getLocationsUnchanged()))
                .locationsFailed(zeroIfNull(runLog.getLocationsFailed()))
                .startedAt(runLog.getStartedAt())
                .completedAt(runLog.getCompletedAt())
                .correlationId(runLog.getCorrelationId())
                .build();
    }

    private SyncLogResponse toLogResponse(LocationSyncLogEntity entity) {
        return SyncLogResponse.builder()
                .syncLogId(entity.getSyncLogId())
                .syncRunId(entity.getSyncRunId())
                .scope(entity.getScope().name())
                .outcome(entity.getOutcome().name())
                .correlationId(entity.getCorrelationId())
                .triggeredBy(entity.getTriggeredBy())
                .locationId(entity.getLocationId())
                .hrLocationId(entity.getHrLocationId())
                .payload(entity.getPayload())
                .errorMessage(entity.getErrorMessage())
                .locationsProcessed(entity.getLocationsProcessed())
                .locationsCreated(entity.getLocationsCreated())
                .locationsUpdated(entity.getLocationsUpdated())
                .locationsUnchanged(entity.getLocationsUnchanged())
                .locationsFailed(entity.getLocationsFailed())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private static int zeroIfNull(@Nullable Integer value) {
        return value == null ? 0 : value;
    }

    private static @Nullable String normalizeKey(@Nullable String idempotencyKey) {
        return idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey;
    }

    private static @Nullable String truncate(@Nullable String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /** Command envelope for the owner's {@code location.commands.v1} listener. */
    record ReplayCommand(@NonNull String commandType, @NonNull Payload payload) {
        record Payload(@Nullable String since, @Nullable String until) {}
    }
}
