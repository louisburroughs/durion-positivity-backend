package com.positivity.accounting.internal.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.EventOutbox.OutboxStatus;

/**
 * Repository for EventOutbox persistence operations.
 */
@Repository
public interface EventOutboxRepository extends JpaRepository<EventOutbox, UUID> {

    /**
     * Find pending events for processing, ordered by creation time (FIFO).
     * 
     * @param status   the outbox status to filter by
     * @param pageable pagination parameters
     * @return list of pending outbox entries
     */
    @NonNull
    List<EventOutbox> findByStatusOrderByCreatedAtAsc(@NonNull OutboxStatus status, @NonNull Pageable pageable);

    /**
     * Find pending events that haven't been attempted recently (for retry logic).
     * 
     * @param status        the outbox status to filter by
     * @param beforeAttempt only include events last attempted before this time
     * @param pageable      pagination parameters
     * @return list of pending outbox entries eligible for retry
     */
    @NonNull
    @Query("SELECT e FROM EventOutbox e WHERE e.status = :status " +
            "AND (e.lastAttemptAt IS NULL OR e.lastAttemptAt < :beforeAttempt) " +
            "ORDER BY e.createdAt ASC")
    List<EventOutbox> findPendingForRetry(
            @Param("status") @NonNull OutboxStatus status,
            @Param("beforeAttempt") @NonNull Instant beforeAttempt,
            @NonNull Pageable pageable);

    /**
     * Count pending events by aggregate ID (for monitoring).
     * 
     * @param aggregateId the aggregate ID
     * @param status      the outbox status
     * @return count of pending events
     */
    long countByAggregateIdAndStatus(@NonNull UUID aggregateId, @NonNull OutboxStatus status);

    /**
     * Delete old published events (for cleanup/archival).
     * 
     * @param status     the outbox status
     * @param beforeDate only delete events published before this date
     * @return number of deleted records
     */
    @Modifying
    @Query("DELETE FROM EventOutbox e WHERE e.status = :status AND e.publishedAt < :beforeDate")
    int deleteOldPublishedEvents(
            @Param("status") @NonNull OutboxStatus status,
            @Param("beforeDate") @NonNull Instant beforeDate);
}
