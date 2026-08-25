package com.positivity.domainevents;

import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The publisher-side companion to {@link ReplicaVersionGuard} (#1486): computes the {@code
 * updatedAt} value a caller stamps to <em>dirty an aggregate row</em> whose fact is about to be
 * published after a mutation that only touched attribute or child tables.
 *
 * <p>The strictly-advancing {@code aggregateVersion} contract relies on Hibernate's {@code
 * @Version} increment, and Hibernate increments only a <em>dirty</em> row. Stamping {@code
 * Instant.now(clock)} is almost always enough to dirty it — but not when the clock reading ties
 * the row's current {@code updatedAt} (a mutation and its child-table follow-up inside one clock
 * tick, or timestamp truncation): an equal value is no change, the row stays clean, the flush has
 * no increment to apply, and the fact goes out carrying new content under an unchanged version —
 * the exact tie #1486 exists to make impossible.
 *
 * <p>{@link #monotonicUpdatedAt(Instant, Clock)} therefore returns the clock's reading only when
 * it is strictly after the current value, and one millisecond past the current value otherwise —
 * always a change, so the row is always dirtied and the version always advances. One millisecond
 * stays representable through every timestamp precision in use (Postgres and H2 store
 * microseconds).
 */
public final class AggregateTouch {

    private AggregateTouch() {}

    /**
     * The next {@code updatedAt} to stamp on an aggregate row being dirtied for publication:
     * strictly after {@code current}, so the assignment is never a same-value no-op.
     *
     * @param current the row's current {@code updatedAt}; {@code null} for a row never stamped
     * @param clock   the caller's injected clock
     * @return the clock's reading, or {@code current.plusMillis(1)} when the reading does not
     *     advance past it
     */
    public static Instant monotonicUpdatedAt(@Nullable Instant current, Clock clock) {
        Instant now = clock.instant();
        return current != null && !now.isAfter(current) ? current.plusMillis(1) : now;
    }
}
