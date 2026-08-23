package com.positivity.order.internal.repository;

import com.positivity.order.internal.entity.OutboxEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Unpublished backlog depth — drives the {@code order.outbox.pending} gauge (#1458). */
    long countByPublishedAtIsNull();

    /**
     * Oldest unpublished row (drain head) — drives the {@code order.outbox.oldest.age.seconds} gauge
     * and the always-UP {@code outbox} health details (#1458).
     */
    Optional<OutboxEvent> findFirstByPublishedAtIsNullOrderByIdAsc();

    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();
}
