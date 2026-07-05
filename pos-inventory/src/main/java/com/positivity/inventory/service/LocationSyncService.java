package com.positivity.inventory.service;

import com.positivity.inventory.internal.dto.sync.LocationSyncRunResponse;
import com.positivity.inventory.internal.dto.sync.SyncLogResponse;
import com.positivity.inventory.internal.enums.LocationSyncOutcome;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Synchronizes the local {@code location_ref} roster from pos-location and
 * exposes the sync audit trail (CAP-214 #40).
 */
public interface LocationSyncService {

    /**
     * Runs one bulk roster sync against pos-location, idempotently upserting
     * local location refs and recording an audit trail. When an
     * {@code idempotencyKey} of a previous run is supplied, that run's
     * summary is returned instead of starting a new one.
     */
    @NonNull
    LocationSyncRunResponse triggerSync(
            @NonNull String triggeredBy, @Nullable String idempotencyKey, @Nullable String correlationId);

    /**
     * Lists sync log entries, newest first, optionally filtered by outcome.
     */
    @NonNull
    List<SyncLogResponse> listSyncLogs(@Nullable LocationSyncOutcome outcome, int page, int size);

    /**
     * Returns one sync log entry by id.
     */
    @NonNull
    SyncLogResponse getSyncLog(@NonNull UUID syncLogId);
}
