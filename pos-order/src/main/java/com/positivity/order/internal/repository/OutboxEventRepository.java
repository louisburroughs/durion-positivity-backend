package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Unpublished backlog depth — drives the {@code order.outbox.pending} gauge (#1458). */
    long countByPublishedAtIsNull();

    /**
     * Oldest unpublished row (drain head) — drives the {@code order.outbox.oldest.age.seconds} gauge
     * and the always-UP {@code outbox} health details (#1458).
     */
    Optional<DrainHeadView> findFirstByPublishedAtIsNullOrderByIdAsc();

    /** Closed projection for the drain head so health polls never hydrate the payload column. */
    interface DrainHeadView {
        Instant getCreatedAt();

        int getAttempts();
    }

    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    /**
     * Published rows of one topic created in a window, for reconciliation-manifest assembly (ADR-0044 §4,
     * #2579). Callers widen the window slightly: {@code createdAt} and the payload's eventId are stamped
     * microseconds apart.
     */
    @NonNull
    List<OutboxEvent> findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(
            @NonNull String topic, @NonNull Instant from, @NonNull Instant to);

    /**
     * Re-queues {@code tenantId}'s already-published rows of one topic created at or after {@code since}
     * (ADR-0044 §4 drift repair, #2579). The publisher re-sends them with their original envelopes, and so
     * their original event ids, which consumers dedupe on. A global table: the tenant is a column here,
     * not a policy, so the filter is explicit.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutboxEvent e set e.publishedAt = null, e.attempts = 0, e.lastError = null"
            + " where e.publishedAt is not null and e.tenantId = :tenantId and e.topic = :topic"
            + " and e.createdAt >= :since")
    int markForReplaySince(
            @Param("tenantId") @NonNull UUID tenantId,
            @Param("topic") @NonNull String topic,
            @Param("since") @NonNull Instant since);

    /** Bounded variant of {@link #markForReplaySince}: only the rows created in {@code [since, until)}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutboxEvent e set e.publishedAt = null, e.attempts = 0, e.lastError = null"
            + " where e.publishedAt is not null and e.tenantId = :tenantId and e.topic = :topic"
            + " and e.createdAt >= :since and e.createdAt < :until")
    int markForReplayBetween(
            @Param("tenantId") @NonNull UUID tenantId,
            @Param("topic") @NonNull String topic,
            @Param("since") @NonNull Instant since,
            @Param("until") @NonNull Instant until);
}
