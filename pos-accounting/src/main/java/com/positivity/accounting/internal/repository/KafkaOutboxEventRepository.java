package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.KafkaOutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence for the Kafka transactional outbox ({@code kafka_event_outbox}, ADR-0044 §4). */
public interface KafkaOutboxEventRepository extends JpaRepository<KafkaOutboxEvent, UUID> {

    /** Oldest unpublished rows first — UUIDv7 ids are time-ordered, preserving publish order. */
    @NonNull
    List<KafkaOutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    /**
     * Published events of one topic created in a window, for reconciliation-manifest assembly
     * (ADR-0044 §4; CAP:550 S16, #2512). Callers widen the window slightly: {@code createdAt} and
     * the payload's eventId are stamped microseconds apart.
     */
    @NonNull
    List<KafkaOutboxEvent> findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(
            @NonNull String topic, @NonNull Instant from, @NonNull Instant to);

    /**
     * Mark {@code tenantId}'s already-published events created at or after {@code since} for
     * re-publication (ADR-0044 drift repair). The publisher re-sends them; consumers dedupe by eventId.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update KafkaOutboxEvent e set e.publishedAt = null, e.attempts = 0, e.lastError = null"
            + " where e.publishedAt is not null and e.tenantId = :tenantId and e.createdAt >= :since")
    int markForReplaySince(@Param("tenantId") @NonNull UUID tenantId, @Param("since") @NonNull Instant since);

    /**
     * Bounded variant for manifest-driven drift repair: re-queue only {@code tenantId}'s rows of the
     * drifted window instead of everything since {@code since}.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update KafkaOutboxEvent e set e.publishedAt = null, e.attempts = 0, e.lastError = null"
            + " where e.publishedAt is not null and e.tenantId = :tenantId and e.createdAt >= :since"
            + " and e.createdAt < :until")
    int markForReplayBetween(
            @Param("tenantId") @NonNull UUID tenantId,
            @Param("since") @NonNull Instant since,
            @Param("until") @NonNull Instant until);
}
