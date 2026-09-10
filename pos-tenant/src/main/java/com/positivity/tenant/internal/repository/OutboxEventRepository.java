package com.positivity.tenant.internal.repository;

import com.positivity.tenant.internal.entity.OutboxEvent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Unpublished backlog depth: drives the {@code tenant.outbox.pending} gauge (#1458). */
    long countByPublishedAtIsNull();

    /** Oldest unpublished row (drain head): drives the age gauge and the outbox health details. */
    Optional<DrainHeadView> findFirstByPublishedAtIsNullOrderByIdAsc();

    /** Closed projection for the drain head so health polls never hydrate the payload column. */
    interface DrainHeadView {
        Instant getCreatedAt();

        int getAttempts();
    }

    /** Oldest unpublished rows first; UUIDv7 ids are time-ordered, preserving publish order. */
    @NonNull
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();
}
