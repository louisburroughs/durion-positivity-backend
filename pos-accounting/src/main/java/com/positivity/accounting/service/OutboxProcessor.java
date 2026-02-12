package com.positivity.accounting.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.accounting.internal.dto.APPaymentGLPostingEvent;
import com.positivity.accounting.internal.entity.EventOutbox;
import com.positivity.accounting.internal.entity.EventOutbox.OutboxStatus;
import com.positivity.accounting.internal.repository.EventOutboxRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Background processor that polls the outbox table and publishes pending
 * events.
 * <p>
 * Provides at-least-once delivery semantics with retry logic and exponential
 * backoff.
 * <p>
 * Configuration points:
 * <ul>
 * <li>Fixed rate: how often to poll (default: every 5 seconds)</li>
 * <li>Batch size: how many events to process per poll (default: 10)</li>
 * <li>Retry delay: minimum time between retry attempts (default: 30
 * seconds)</li>
 * <li>Max retries: maximum attempts before marking as FAILED (default: 5)</li>
 * </ul>
 * 
 * @see <a href=
 *      "https://microservices.io/patterns/data/transactional-outbox.html">
 *      Transactional Outbox Pattern</a>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxProcessor {

    private final EventOutboxRepository outboxRepository;
    private final OutboxService outboxService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    private static final int BATCH_SIZE = 10;
    private static final int MAX_RETRIES = 5;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(30);

    /**
     * Scheduled task to process pending outbox events.
     * <p>
     * Runs every 5 seconds. Polls for pending events and publishes them.
     * Implements retry logic with exponential backoff.
     */
    @Scheduled(fixedRate = 5000, initialDelay = 10000)
    public void processPendingEvents() {
        try {
            Instant retryThreshold = Instant.now().minus(RETRY_DELAY);

            List<EventOutbox> pendingEvents = outboxRepository.findPendingForRetry(
                    OutboxStatus.PENDING,
                    retryThreshold,
                    PageRequest.of(0, BATCH_SIZE));

            if (pendingEvents.isEmpty()) {
                return;
            }

            log.debug("Processing {} pending outbox events", pendingEvents.size());

            for (EventOutbox outbox : pendingEvents) {
                processEvent(outbox);
            }

        } catch (Exception e) {
            log.error("Error processing outbox events", e);
        }
    }

    /**
     * Process a single outbox event.
     * 
     * @param outbox the outbox entry to process
     */
    private void processEvent(@NonNull EventOutbox outbox) {
        try {
            Object event = deserializeEvent(outbox);

            // Publish the event
            eventPublisher.publishEvent(event);

            // Mark as published
            outboxService.markAsPublished(outbox.getOutboxId());

            log.info("Event published | outboxId={} | eventId={} | eventType={}",
                    outbox.getOutboxId(), outbox.getEventId(), outbox.getEventType());

        } catch (Exception e) {
            log.error("Failed to publish event | outboxId={} | eventId={} | error={}",
                    outbox.getOutboxId(), outbox.getEventId(), e.getMessage(), e);

            outboxService.markAsFailed(outbox.getOutboxId(), e.getMessage(), MAX_RETRIES);
        }
    }

    /**
     * Deserialize the event payload based on event type.
     * 
     * @param outbox the outbox entry containing the serialized payload
     * @return deserialized event object
     * @throws JsonProcessingException
     * @throws IllegalArgumentException if event type is unsupported
     */
    private @NonNull Object deserializeEvent(@NonNull EventOutbox outbox) throws JsonProcessingException {
        String eventType = outbox.getEventType();

        // Map event type to concrete class
        // TODO: Make this extensible via registry pattern if more event types are added
        Class<?> eventClass = switch (eventType) {
            case "com.positivity.accounting.internal.dto.APPaymentGLPostingEvent" -> APPaymentGLPostingEvent.class;
            default -> throw new IllegalArgumentException("Unsupported event type: " + eventType);
        };

        return objectMapper.readValue(outbox.getPayload(), eventClass);
    }

    /**
     * Scheduled cleanup of old published events.
     * <p>
     * Runs daily at 2 AM. Deletes events published more than 30 days ago.
     */
    @Scheduled(cron = "0 0 2 * * *")
    public void cleanupOldEvents() {
        try {
            Instant thirtyDaysAgo = Instant.now().minus(Duration.ofDays(30));
            int deleted = outboxService.cleanupOldEvents(thirtyDaysAgo);

            if (deleted > 0) {
                log.info("Cleaned up {} old published outbox events", deleted);
            }
        } catch (Exception e) {
            log.error("Error cleaning up old outbox events", e);
        }
    }
}
