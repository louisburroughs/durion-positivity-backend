package com.positivity.accounting.service;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.EventOutbox.OutboxStatus;
import com.positivity.accounting.internal.repository.EventOutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Service for managing event outbox operations.
 * <p>
 * Provides transactional guarantees for event persistence and publication
 * tracking.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxService {

    private final EventOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

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
    @Transactional(propagation = Propagation.MANDATORY)
    public @NonNull EventOutbox saveToOutbox(
            @NonNull UUID eventId,
            @NonNull String aggregateType,
            @NonNull UUID aggregateId,
            @NonNull String eventType,
            @NonNull Object event) {
        try {
            String payload = objectMapper.writeValueAsString(event);

            EventOutbox outbox = EventOutbox.create(
                    eventId,
                    aggregateType,
                    aggregateId,
                    eventType,
                    payload);

            EventOutbox saved = outboxRepository.save(outbox);

            log.debug("Event saved to outbox | eventId={} | aggregateType={} | aggregateId={}",
                    eventId, aggregateType, aggregateId);

            return saved;

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize event to JSON | eventId={} | eventType={}",
                    eventId, eventType, e);
            throw new IllegalStateException("Failed to serialize event for outbox", e);
        }
    }

    /**
     * Marks an outbox entry as successfully published.
     * <p>
     * Uses REQUIRES_NEW to ensure publication status is committed even if
     * the message broker transaction fails.
     * 
     * @param outboxId the outbox entry ID
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsPublished(@NonNull UUID outboxId) {
        outboxRepository.findById(outboxId).ifPresent(outbox -> {
            outbox.setStatus(OutboxStatus.PUBLISHED);
            outbox.setPublishedAt(Instant.now());
            outboxRepository.save(outbox);

            log.debug("Event marked as published | outboxId={} | eventId={}",
                    outboxId, outbox.getEventId());
        });
    }

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAsFailed(@NonNull UUID outboxId, @NonNull String errorMsg, int maxRetries) {
        outboxRepository.findById(outboxId).ifPresent(outbox -> {
            outbox.setRetryCount(outbox.getRetryCount() + 1);
            outbox.setLastError(errorMsg);
            outbox.setLastAttemptAt(Instant.now());

            if (outbox.getRetryCount() >= maxRetries) {
                outbox.setStatus(OutboxStatus.FAILED);
                log.error("Event publication failed after {} retries | outboxId={} | eventId={} | error={}",
                        maxRetries, outboxId, outbox.getEventId(), errorMsg);
            } else {
                log.warn("Event publication failed (retry {}/{}) | outboxId={} | eventId={} | error={}",
                        outbox.getRetryCount(), maxRetries, outboxId, outbox.getEventId(), errorMsg);
            }

            outboxRepository.save(outbox);
        });
    }

    /**
     * Cleanup old published events (for scheduled archival).
     * 
     * @param beforeDate delete events published before this date
     * @return number of deleted records
     */
    @Transactional
    public int cleanupOldEvents(@NonNull Instant beforeDate) {
        int deleted = outboxRepository.deleteOldPublishedEvents(OutboxStatus.PUBLISHED, beforeDate);
        if (deleted > 0) {
            log.info("Cleaned up {} old published events | beforeDate={}", deleted, beforeDate);
        }
        return deleted;
    }
}
