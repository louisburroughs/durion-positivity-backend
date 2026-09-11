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
     * Administrative replay for the calling operator (ADR-0062 §3): the bound tenant's published
     * outbox events created at or after {@code since} — or, when the caller is bound to the platform
     * tenant, which owns no workorder rows of its own, every active tenant's in turn through
     * {@code TenantIterator}. This is the {@code POST /v1/outbox/replay} path; the Kafka
     * {@code workorder.outbox.replay-requested} path always uses {@link #replaySince} for exactly
     * the tenant on the command's header.
     *
     * @return the number of events queued for re-publication across the tenants replayed
     */
    int replaySinceForCaller(@NonNull Instant since);

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
