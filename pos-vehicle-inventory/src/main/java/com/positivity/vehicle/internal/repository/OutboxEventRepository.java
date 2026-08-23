package com.positivity.vehicle.internal.repository;

import com.positivity.vehicle.internal.entity.OutboxEvent;
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

    /** Unpublished backlog depth — drives the {@code vehicle.outbox.pending} gauge (#1458). */
    long countByPublishedAtIsNull();

    /**
     * Oldest unpublished row (drain head) — drives the {@code vehicle.outbox.oldest.age.seconds} gauge
     * and the always-UP {@code outbox} health details (#1458).
     */
    Optional<DrainHeadView> findFirstByPublishedAtIsNullOrderByIdAsc();

    /** Closed projection for the drain head so health polls never hydrate the payload column. */
    interface DrainHeadView {
        Instant getCreatedAt();

        int getAttempts();
    }

    /** Oldest unpublished rows first — UUIDv7 ids are time-ordered, preserving publish order. */
    @NonNull
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    /**
     * Published rows of one topic whose {@code createdAt} falls in the given range, for
     * reconciliation-manifest computation (ADR-0044 §4). Callers pass the window padded by a small
     * slack and re-filter precisely by the eventId's UUIDv7 timestamp, since {@code createdAt} and
     * the payload's eventId are stamped microseconds apart.
     */
    @NonNull
    List<OutboxEvent> findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(
            @NonNull String topic, @NonNull Instant from, @NonNull Instant to);

    /**
     * Mark already-published events created at or after {@code since} for re-publication
     * (ADR-0044 backfill/drift repair). The publisher re-sends them; consumers dedupe by eventId.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutboxEvent e set e.publishedAt = null, e.attempts = 0, e.lastError = null"
            + " where e.publishedAt is not null and e.createdAt >= :since")
    int markForReplaySince(@Param("since") @NonNull Instant since);

    /**
     * Bounded variant for manifest-driven drift repair: re-queue only the drifted window instead
     * of everything since {@code since}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update OutboxEvent e set e.publishedAt = null, e.attempts = 0, e.lastError = null"
            + " where e.publishedAt is not null and e.createdAt >= :since and e.createdAt < :until")
    int markForReplayBetween(@Param("since") @NonNull Instant since, @Param("until") @NonNull Instant until);
}
