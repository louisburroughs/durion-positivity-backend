package com.positivity.workorder.internal.service;

import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Regenerates {@code workorder.workorder.updated} facts from current owner state for workorders
 * whose actual-time fields predate that contract slot (ADR-0044 §6, issue #2021 AC8).
 *
 * <p>Distinct from {@link OutboxReplayService}, and not interchangeable with it. Outbox replay only
 * re-queues rows already sitting in {@code event_outbox}, so it re-sends the exact JSON bytes stored
 * at publish time. A workorder that started or completed before {@code workStartedAt}/{@code
 * completedAt} were added to {@code WorkorderUpdatedV1} has outbox history that carries neither
 * field — replaying it re-sends the same gap. This service instead calls {@link
 * WorkorderFactPublisher#markChanged} against the live row, which builds a brand-new snapshot from
 * current entity state at commit; that is the only way to backfill a field the stored bytes never
 * carried.
 *
 * <p>Scoped to workorders that actually need it: rows with a non-null {@code workStartedAt} and/or
 * {@code completedAt} (see {@code WorkorderRepository#findActualTimeBackfillPage}). A workorder that
 * never started has nothing new to publish, so walking the whole table to skip most of it would
 * multiply the cost of a repair that only concerns a fraction of it.
 *
 * <p>Because the backfill dirties nothing — it only reads the row and asks the publisher to snapshot
 * it — the fact it re-emits carries the <strong>same</strong> {@code aggregateVersion} the row
 * already published at. That is intended, not a defect to "fix": pos-shop-manager's replica stale
 * guard is strictly-below (a fact whose version is not strictly greater than the one the replica
 * already holds is a no-op), so re-applying an equal version repairs a stale or missing replica field
 * without disturbing a replica that is already current.
 *
 * <p>Each run is <strong>bounded</strong> and keyset-paginated by id, for the same reason as
 * {@code pos-location}'s {@code FactBackfillService} (issue #1668): it executes on the Kafka
 * command-listener thread, and an unbounded walk risks exceeding {@code max.poll.interval.ms},
 * evicting the consumer before it commits its offset — which redelivers the same command and
 * restarts the whole walk. A run therefore stops after a configured number of rows and reports the
 * cursor to resume from.
 */
public interface WorkorderFactBackfillService {

    /**
     * Outcome of one bounded backfill run.
     *
     * @param published number of facts queued to the outbox
     * @param lastId highest workorder id processed, or null when nothing was processed; pass it back
     *     as the next run's {@code afterId} to resume. A cursor belongs to one tenant's id space, so
     *     it is only meaningful alongside {@code tenantId}
     * @param tenantId the tenant {@code lastId} belongs to. For a platform operator's fan-out this
     *     is the tenant the run stopped inside when the budget ran out; pass both back to finish it
     * @param more true when the bound was reached and rows remain. For a fan-out, true when any
     *     tenant's own run reached its bound or a tenant was not reached at all
     */
    record BackfillResult(
            int published, @Nullable UUID lastId, @Nullable UUID tenantId, boolean more) {}

    /**
     * Regenerate fresh facts for the calling tenant (ADR-0062 §3) — or, when the caller is bound to
     * the platform tenant, which owns no workorder rows of its own, every active tenant's in turn
     * through {@code TenantIterator}, mirroring {@code OutboxReplayServiceImpl#replaySinceForCaller}.
     *
     * <p>One run carries <strong>one</strong> budget. A platform fan-out spends it across tenants
     * rather than giving each tenant its own, because a per-tenant budget lets a single command do
     * {@code maxRowsPerRun x tenantCount} rows of work on the Kafka listener thread — the eviction
     * the bound exists to prevent. When the budget runs out mid-fleet the result names the tenant it
     * stopped inside and that tenant's cursor, and the operator sends both straight back; without a
     * resume point a tenant holding more rows than one budget could never be finished, because every
     * re-run would restart it from the beginning.
     *
     * @param afterId exclusive cursor for a single tenant's walk; null starts from the beginning.
     *     Meaningful only alongside {@code tenantId} for a platform caller, since each tenant's id
     *     space is independent; ignored with a warning otherwise.
     * @param tenantId the single tenant to walk. A platform caller uses it to resume one tenant; a
     *     non-platform caller may only name its own tenant, and naming another is refused rather
     *     than silently run, since the command arrives over Kafka.
     * @return the outcome of the run — summed across tenants for a platform-tenant fan-out
     */
    @NonNull
    BackfillResult backfillForCaller(@Nullable UUID afterId, @Nullable UUID tenantId);
}
