package com.positivity.accounting.internal.repository;

import com.positivity.accounting.internal.entity.KafkaOutboxEvent;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the Kafka transactional outbox ({@code kafka_event_outbox}, ADR-0044 §4). */
public interface KafkaOutboxEventRepository extends JpaRepository<KafkaOutboxEvent, UUID> {

    /** Oldest unpublished rows first — UUIDv7 ids are time-ordered, preserving publish order. */
    @NonNull
    List<KafkaOutboxEvent> findTop100ByPublishedAtIsNullOrderByIdAsc();
}
