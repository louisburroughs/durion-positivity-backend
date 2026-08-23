package com.positivity.supplier.internal.repository;

import com.positivity.supplier.internal.entity.SupplierOutboxEventEntity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
