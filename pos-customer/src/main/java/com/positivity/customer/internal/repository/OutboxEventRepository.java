package com.positivity.customer.internal.repository;

import com.positivity.customer.internal.entity.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /** Oldest unpublished rows first — UUIDv7 ids are time-ordered, preserving publish order. */
    @NonNull
    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();
}
