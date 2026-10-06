package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Drain queue for supplier domain events (ADR-0044 §4). */
public interface SupplierOutboxEventRepository extends JpaRepository<SupplierOutboxEventEntity, UUID> {

    /**
     * Unpublished rows in id order. UUIDv7 ids are time-ordered, so this preserves the order the
     * events were written — which is what keeps an import's completion event behind its chunks.
     */
    List<SupplierOutboxEventEntity> findTop100ByPublishedAtIsNullOrderByIdAsc();

    /** Unpublished backlog depth — drives the {@code supplier.outbox.pending} gauge (#1458). */
    long countByPublishedAtIsNull();

    /**
     * Oldest unpublished row (drain head) — drives the {@code supplier.outbox.oldest.age.seconds}
     * gauge and the always-UP {@code outbox} health details (#1458).
     */
    Optional<DrainHeadView> findFirstByPublishedAtIsNullOrderByIdAsc();

    /** Closed projection for the drain head so health polls never hydrate the payload column. */
    interface DrainHeadView {
        Instant getCreatedAt();

        int getAttempts();
    }

    /**
     * Re-queues {@code tenantId}'s already-published rows of one topic created in {@code [since, until)}
     * (ADR-0044 §4 drift repair, #2516). The publisher re-sends them with their original envelopes — and so
     * their original event ids, which consumers dedupe on. A global table: the tenant is a column here, not a
     * policy, so the filter is explicit.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SupplierOutboxEventEntity e set e.publishedAt = null, e.attempts = 0, e.lastError = null"
            + " where e.publishedAt is not null and e.tenantId = :tenantId and e.topic = :topic"
            + " and e.createdAt >= :since and e.createdAt < :until")
    int markForReplayBetween(
            @Param("tenantId") @NonNull UUID tenantId,
            @Param("topic") @NonNull String topic,
            @Param("since") @NonNull Instant since,
            @Param("until") @NonNull Instant until);
}
