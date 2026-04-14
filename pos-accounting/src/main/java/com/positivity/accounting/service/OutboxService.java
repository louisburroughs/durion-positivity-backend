package com.positivity.accounting.service;

import com.positivity.accounting.internal.entity.EventOutbox;
import java.time.Instant;
import java.util.UUID;

public interface OutboxService {

    /**
     * Persists an event to the outbox for eventual publication.
     * <p>
     * This method MUST be called within the same transaction as the business
     * operation
     * to ensure atomicity.
     *
     * @param eventId       unique event identifier (idempotency key)
     * @param aggregateType type of aggregate (e.g., "APPayment")
     * @param aggregateId   ID of the aggregate
     * @param eventType     fully qualified event class name
     * @param event         the event object to serialize and persist
     * @return the persisted outbox entry
     * @throws RuntimeException if JSON serialization fails
     */
    EventOutbox saveToOutbox(UUID eventId, String aggregateType, UUID aggregateId, String eventType, Object event);

    /**
     * Marks an outbox entry as successfully published.
     * <p>
     * Uses REQUIRES_NEW to ensure publication status is committed even if
     * the message broker transaction fails.
     *
     * @param outboxId the outbox entry ID
     */
    void markAsPublished(UUID outboxId);

    /**
     * Records a failed publication attempt.
     * <p>
     * Increments retry count and stores error message. Marks as FAILED if max
     * retries exceeded.
     *
     * @param outboxId   the outbox entry ID
     * @param errorMsg   error message from publication attempt
     * @param maxRetries maximum number of retries before marking as FAILED
     */
    void markAsFailed(UUID outboxId, String errorMsg, int maxRetries);

    /**
     * Cleanup old published events (for scheduled archival).
     *
     * @param beforeDate delete events published before this date
     * @return number of deleted records
     */
    int cleanupOldEvents(Instant beforeDate);
}
