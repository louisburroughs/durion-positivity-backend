package com.positivity.catalog.internal.repository;

import com.positivity.catalog.internal.entity.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Outbox accessor for the catalog fact producer (ADR-0044 §4, #924). */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Oldest unpublished rows first — UUIDv7 ids are time-ordered, preserving publish order. */
    @NonNull
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();

    /** Published rows of one topic in a created_at window, for reconciliation-manifest computation. */
    @NonNull
    List<OutboxEvent> findByTopicAndPublishedAtIsNotNullAndCreatedAtBetween(
            @NonNull String topic, @NonNull Instant from, @NonNull Instant to);
}
