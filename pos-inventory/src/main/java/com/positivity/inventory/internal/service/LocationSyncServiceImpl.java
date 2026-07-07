package com.positivity.inventory.internal.service;

import com.positivity.inventory.internal.client.LocationRosterClient;
import com.positivity.inventory.internal.client.LocationRosterClient.LocationRosterEntry;
import com.positivity.inventory.internal.dto.sync.LocationSyncRunResponse;
import com.positivity.inventory.internal.dto.sync.SyncLogResponse;
import com.positivity.inventory.internal.entity.LocationRefEntity;
import com.positivity.inventory.internal.entity.LocationSyncLogEntity;
import com.positivity.inventory.internal.enums.LocationSyncLogScope;
import com.positivity.inventory.internal.enums.LocationSyncOutcome;
import com.positivity.inventory.internal.exception.ResourceNotFoundException;
import com.positivity.inventory.internal.repository.LocationRefRepository;
import com.positivity.inventory.internal.repository.LocationSyncLogRepository;
import com.positivity.inventory.service.LocationSyncService;
import com.positivity.shared.id.UUIDv7Generator;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bulk REST sync of the location roster from pos-location into the local
 * {@code location_ref} table (CAP-214 #40). Event-driven sync is a
 * follow-up; this covers the on-demand/bulk mode of the story.
 */
@Service
@RequiredArgsConstructor
public class LocationSyncServiceImpl implements LocationSyncService {

    private static final Logger log = LoggerFactory.getLogger(LocationSyncServiceImpl.class);
    private static final String ACTIVE_STATUS = "ACTIVE";

    private final LocationRosterClient locationRosterClient;
    private final LocationRefRepository locationRefRepository;
    private final LocationSyncLogRepository locationSyncLogRepository;
    private final Clock clock;

    // Intentionally not @Transactional: the roster fetch is a remote call, each
    // upsert commits on its own, and the FAILED audit row must survive the rethrow.
    @Override
    @NonNull
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
        List<LocationRosterEntry> roster;
        try {
            roster = locationRosterClient.fetchRoster();
        } catch (RuntimeException ex) {
            locationSyncLogRepository.save(LocationSyncLogEntity.builder()
                    .syncRunId(syncRunId)
                    .scope(LocationSyncLogScope.RUN)
                    .outcome(LocationSyncOutcome.FAILED)
                    .correlationId(correlationId)
                    .idempotencyKey(normalizeKey(idempotencyKey))
                    .triggeredBy(triggeredBy)
                    .errorMessage(truncate(ex.getMessage(), 2000))
                    .locationsProcessed(0)
                    .locationsCreated(0)
                    .locationsUpdated(0)
                    .locationsUnchanged(0)
                    .locationsFailed(0)
                    .startedAt(startedAt)
                    .completedAt(clock.instant())
                    .build());
            throw ex;
        }

        int created = 0;
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (LocationRosterEntry entry : roster) {
            if (entry.id() == null || entry.name() == null || entry.name().isBlank()) {
                failed++;
                locationSyncLogRepository.save(LocationSyncLogEntity.builder()
                        .syncRunId(syncRunId)
                        .scope(LocationSyncLogScope.RECORD)
                        .outcome(LocationSyncOutcome.INVALID_PAYLOAD)
                        .correlationId(correlationId)
                        .triggeredBy(triggeredBy)
                        .locationId(entry.id())
                        .hrLocationId(entry.hrLocationId())
                        .payload(truncate(entry.toString(), 4000))
                        .errorMessage("Roster record is missing required fields (id, name)")
                        .build());
                continue;
            }
            switch (upsert(entry)) {
                case CREATED -> created++;
                case UPDATED -> updated++;
                case UNCHANGED -> unchanged++;
            }
        }

        LocationSyncOutcome outcome = failed == 0
                ? LocationSyncOutcome.OK
                : (failed == roster.size() ? LocationSyncOutcome.FAILED : LocationSyncOutcome.PARTIAL);
        LocationSyncLogEntity runLog = locationSyncLogRepository.save(LocationSyncLogEntity.builder()
                .syncRunId(syncRunId)
                .scope(LocationSyncLogScope.RUN)
                .outcome(outcome)
                .correlationId(correlationId)
                .idempotencyKey(normalizeKey(idempotencyKey))
                .triggeredBy(triggeredBy)
                .locationsProcessed(roster.size())
                .locationsCreated(created)
                .locationsUpdated(updated)
                .locationsUnchanged(unchanged)
                .locationsFailed(failed)
                .startedAt(startedAt)
                .completedAt(clock.instant())
                .build());
        log.info(
                "Location sync run {} finished: {} processed, {} created, {} updated, {} unchanged, {} failed",
                syncRunId,
                roster.size(),
                created,
                updated,
                unchanged,
                failed);
        return toRunResponse(runLog);
    }

    private enum UpsertResult {
        CREATED,
        UPDATED,
        UNCHANGED
    }

    private UpsertResult upsert(LocationRosterEntry entry) {
        boolean entryActive = ACTIVE_STATUS.equalsIgnoreCase(entry.status());
        Optional<LocationRefEntity> existing = locationRefRepository.findByLocationId(entry.id());
        if (existing.isEmpty()) {
            locationRefRepository.save(LocationRefEntity.builder()
                    .locationId(entry.id())
                    .hrLocationId(entry.hrLocationId())
                    .name(entry.name())
                    .code(entry.code())
                    .status(entry.status() == null ? ACTIVE_STATUS : entry.status())
                    .timezone(entry.timezone())
                    .active(entry.status() == null || entryActive)
                    .sourceUpdatedAt(entry.updatedAt())
                    .deactivatedAt(entry.status() != null && !entryActive ? clock.instant() : null)
                    .build());
            return UpsertResult.CREATED;
        }

        LocationRefEntity ref = existing.get();
        boolean changed = false;
        if (!Objects.equals(ref.getName(), entry.name())) {
            ref.setName(entry.name());
            changed = true;
        }
        // Never overwrite existing fields with nulls (story: missing HR fields).
        if (entry.code() != null && !Objects.equals(ref.getCode(), entry.code())) {
            ref.setCode(entry.code());
            changed = true;
        }
        if (entry.hrLocationId() != null && !Objects.equals(ref.getHrLocationId(), entry.hrLocationId())) {
            ref.setHrLocationId(entry.hrLocationId());
            changed = true;
        }
        if (entry.timezone() != null && !Objects.equals(ref.getTimezone(), entry.timezone())) {
            ref.setTimezone(entry.timezone());
            changed = true;
        }
        if (entry.status() != null && !Objects.equals(ref.getStatus(), entry.status())) {
            ref.setStatus(entry.status());
            if (ref.isActive() && !entryActive) {
                ref.setDeactivatedAt(clock.instant());
            } else if (entryActive) {
                ref.setDeactivatedAt(null);
            }
            ref.setActive(entryActive);
            changed = true;
        }
        if (entry.updatedAt() != null && !Objects.equals(ref.getSourceUpdatedAt(), entry.updatedAt())) {
            ref.setSourceUpdatedAt(entry.updatedAt());
            changed = true;
        }
        if (!changed) {
            return UpsertResult.UNCHANGED;
        }
        locationRefRepository.save(ref);
        return UpsertResult.UPDATED;
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
}
