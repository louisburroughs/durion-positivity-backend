package com.positivity.peoplecontact.service;

import java.time.Instant;
import org.jspecify.annotations.NonNull;

/**
 * Re-queues already-published outbox events for re-publication (ADR-0044 §4 backfill/drift
 * repair). Consumers dedupe by eventId, so replay is idempotent.
 */
public interface OutboxReplayService {

    /** Re-queue published events created at or after {@code since}. Returns the count queued. */
    int replaySince(@NonNull Instant since);

    /** Re-queue published events created in {@code [since, until)}. Returns the count queued. */
    int replayBetween(@NonNull Instant since, @NonNull Instant until);
}
