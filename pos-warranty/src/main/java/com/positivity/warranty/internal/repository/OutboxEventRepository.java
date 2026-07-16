package com.positivity.warranty.internal.repository;

import com.positivity.warranty.internal.entity.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Oldest unpublished rows first — UUIDv7 ids are time-ordered, preserving publish order. */
    @NonNull
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    /**
     * Published events of one topic created in a window, for reconciliation-manifest assembly
     * (ADR-0044 §4). Callers widen the window slightly: {@code createdAt} and the payload's
     * eventId are stamped microseconds apart.
     */
    @NonNull
    List<OutboxEvent> findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(
            @NonNull String topic, @NonNull Instant from, @NonNull Instant to);

    /**
     * Mark already-published events created at or after {@code since} for re-publication
     * (ADR-0044 backfill/drift repair). The publisher re-sends them; consumers dedupe by eventId.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update OutboxEvent e set e.publishedAt = null, e.attempts = 0, e.lastError = null"
                    + " where e.publishedAt is not null and e.createdAt >= :since")
    int markForReplaySince(@org.springframework.data.repository.query.Param("since") @NonNull Instant since);

    /**
     * Bounded variant for manifest-driven drift repair: re-queue only the drifted window instead
     * of everything since {@code since}.
     */
    @org.springframework.data.jpa.repository.Modifying(clearAutomatically = true, flushAutomatically = true)
    @org.springframework.data.jpa.repository.Query(
            "update OutboxEvent e set e.publishedAt = null, e.attempts = 0, e.lastError = null"
                    + " where e.publishedAt is not null and e.createdAt >= :since and e.createdAt < :until")
    int markForReplayBetween(
            @org.springframework.data.repository.query.Param("since") @NonNull Instant since,
            @org.springframework.data.repository.query.Param("until") @NonNull Instant until);
}
