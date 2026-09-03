package com.positivity.location.internal.config;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Regenerates bay and mobile-unit facts from current owner state (ADR-0044 §6, issue #1668).
 *
 * <p>Distinct from {@link OutboxReplayService}, and not interchangeable with it. Outbox replay
 * re-queues rows that are already in {@code event_outbox}, so it can only re-send facts that were
 * published at least once. Bays and mobile units published nothing before #1668, so every row that
 * existed when this story shipped has no outbox history to replay — a purely forward-only stream
 * would leave those units permanently invisible to pos-workorder's dispatch board and
 * pos-shop-manager's unit roster, which is the exact gap #1656 and #1658 were blocked on.
 *
 * <p>This service instead reads the owner's own tables and emits a current-state fact per row, so a
 * consumer starting from an empty replica converges without pos-location having to retain history.
 * It is idempotent by the platform's consumer rule: a replica applies an equal version and skips
 * only a strictly-greater one, so re-running a backfill over rows a consumer already holds is a
 * no-op, while a replica holding a version but wrong or missing data is repaired.
 *
 * <p>Each run is <strong>bounded</strong> and resumable. It executes on the Kafka command-listener
 * thread, which is shared with {@code location.outbox.replay-requested}: an unbounded walk of every
 * row would risk exceeding {@code max.poll.interval.ms}, and an evicted consumer never commits its
 * offset, so the same command is redelivered after rebalance and the whole backfill restarts —
 * an unbounded loop that re-floods the outbox each cycle while drift-repair replay requests from
 * other modules queue behind it. A run therefore stops after a configured number of rows and
 * reports the cursor to resume from.
 */
public interface FactBackfillService {

    /**
     * Outcome of one bounded backfill run.
     *
     * @param published number of facts queued to the outbox
     * @param lastId highest aggregate id processed, or null when nothing was processed; pass it
     *     back as the next run's {@code afterId} to continue
     * @param more true when the bound was reached and rows remain
     */
    record BackfillResult(int published, @Nullable UUID lastId, boolean more) {}

    /**
     * Emit current-state {@code location.bay.updated} facts, starting after {@code afterId}.
     *
     * @param afterId exclusive cursor; null starts from the beginning
     */
    BackfillResult backfillBays(@Nullable UUID afterId);

    /**
     * Emit current-state {@code location.mobile-unit.updated} facts, starting after
     * {@code afterId}.
     *
     * @param afterId exclusive cursor; null starts from the beginning
     */
    BackfillResult backfillMobileUnits(@Nullable UUID afterId);
}
