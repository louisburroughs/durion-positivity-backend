package com.positivity.workorder.service;

import java.time.Instant;
import org.jspecify.annotations.NonNull;

/**
 * Administrative re-emit of already-published outbox events (ADR-0044 §4 backfill/bootstrap).
 *
 * <p>Used to seed a new consumer replica or repair drift: matching rows are marked unpublished so
 * the outbox publisher re-sends them. Consumers are idempotent by {@code eventId}, so replay is
 * harmless to consumers that already processed the events.
 */
public interface OutboxReplayService {

    /**
     * Mark published outbox events created at or after {@code since} for re-publication.
     *
     * @param since lower bound (inclusive) on event creation time
     * @return the number of events queued for re-publication
     */
    int replaySince(@NonNull Instant since);
}
