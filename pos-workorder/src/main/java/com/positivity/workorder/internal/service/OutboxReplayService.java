package com.positivity.workorder.internal.service;

import java.time.Instant;
import org.jspecify.annotations.NonNull;

/**
 * Administrative re-emit of already-published outbox events (ADR-0044 §4 backfill/bootstrap).
 *
 * <p>Used to seed a new consumer replica or repair drift: matching rows are marked unpublished so
 * the outbox publisher re-sends them. Consumers are idempotent by {@code eventId}, so replay is
 * harmless to consumers that already processed the events. Only the bound tenant's rows are
 * replayed (ADR-0062 §3): the command carries the requesting manifest's tenant header, so one
 * tenant's drift never re-sends another tenant's events.
 */
public interface OutboxReplayService {

    /**
     * Mark the bound tenant's published outbox events created at or after {@code since} for
     * re-publication.
     *
     * @param since lower bound (inclusive) on event creation time
     * @return the number of events queued for re-publication
     */
    int replaySince(@NonNull Instant since);

    /**
     * Mark the bound tenant's published outbox events created in {@code [since, until)} for
     * re-publication — the
     * bounded form used by manifest-driven drift repair, so one drifted window never triggers a
     * full-history replay.
     *
     * @return the number of events queued for re-publication
     */
    int replayBetween(@NonNull Instant since, @NonNull Instant until);
}
