package com.positivity.accounting.internal.config;

import java.time.Instant;
import org.jspecify.annotations.NonNull;

/**
 * Re-queues already-published accounting facts of the bound tenant for re-publication (ADR-0044 §4
 * drift repair; CAP:550 S16, #2512). Consumers dedupe by eventId, so a replay is idempotent.
 */
public interface OutboxReplayService {

    /** Re-queue the bound tenant's facts created at or after {@code since}; returns how many. */
    int replaySince(@NonNull Instant since);

    /** Re-queue the bound tenant's facts created in {@code [since, until)}; returns how many. */
    int replayBetween(@NonNull Instant since, @NonNull Instant until);
}
