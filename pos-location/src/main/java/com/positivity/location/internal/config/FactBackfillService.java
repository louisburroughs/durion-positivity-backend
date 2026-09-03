package com.positivity.location.internal.config;

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
 * <p>Facts are written through the transactional outbox like any other, so backfill and live
 * traffic share one ordering domain per aggregate and a backfilled fact can never overtake a
 * concurrent live mutation: both carry the aggregate's {@code @Version} at write time.
 */
public interface FactBackfillService {

    /**
     * Emit a current-state {@code location.bay.updated} fact for every bay. Returns the count
     * published.
     */
    int backfillBays();

    /**
     * Emit a current-state {@code location.mobile-unit.updated} fact for every mobile unit. Returns
     * the count published.
     */
    int backfillMobileUnits();
}
