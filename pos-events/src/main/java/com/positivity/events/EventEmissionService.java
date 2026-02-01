package com.positivity.events;

import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Core service for executing event-emitting operations with consistent
 * logging, timing, and event publication.
 * 
 * This service encapsulates the common event emission logic used by both
 * {@link EmitEventAspect} (for AOP-based interception) and
 * {@link EmitEventProxy} (for proxy-based interception), ensuring
 * consistent behavior across all event emission mechanisms.
 * 
 * The service handles:
 * <ul>
 * <li>Event start/end timestamp logging</li>
 * <li>Method execution with error handling</li>
 * <li>Domain event publication via Spring's ApplicationEventPublisher</li>
 * <li>Error logging with timestamps</li>
 * </ul>
 * 
 * @see EmitEvent
 * @see EmitEventAspect
 * @see EmitEventProxy
 * @see EventEmitted
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventEmissionService {

    private final ApplicationEventPublisher publisher;

    /**
     * Functional interface for operations that can throw any Throwable.
     * Used to wrap method invocations that may throw checked exceptions.
     * 
     * @param <T> the return type of the operation
     */
    @FunctionalInterface
    public interface ThrowingSupplier<T> {
        T get() throws Throwable;
    }

    /**
     * Executes an operation with event emission tracking.
     * 
     * @param <T>        the return type of the operation
     * @param eventId    the unique identifier for the event
     * @param apiVersion the API version that triggered the event
     * @param operation  the operation to execute (may throw Throwable)
     * @return the result of the operation
     * @throws Throwable if the operation throws any exception
     */
    public <T> T executeWithEventEmission(String eventId, String apiVersion, ThrowingSupplier<T> operation)
            throws Throwable {
        long start = Instant.now().toEpochMilli();
        log.info("[EVENT-START] id={} apiVersion={} timestamp={}", eventId, apiVersion, start);

        try {
            T result = operation.get();
            long end = Instant.now().toEpochMilli();
            long elapsedMs = end - start;
            log.info("[EVENT-END] id={} apiVersion={} timestamp={} elapsedMs={}", eventId, apiVersion, end, elapsedMs);

            publishEvent(eventId, apiVersion, end, elapsedMs);
            return result;
        } catch (Throwable e) {
            long end = Instant.now().toEpochMilli();
            long elapsedMs = end - start;
            log.error("[EVENT-ERROR] id={} apiVersion={} timestamp={} elapsedMs={} error={}", eventId, apiVersion, end,
                    elapsedMs,
                    e.getMessage());
            throw e;
        }
    }

    /**
     * Publishes an EventEmitted domain event.
     * 
     * @param eventId    the unique identifier for the event
     * @param apiVersion the API version that triggered the event
     * @param timestamp  the event completion timestamp
     * @param elapsedMs  the elapsed time in milliseconds
     */
    private void publishEvent(String eventId, String apiVersion, long timestamp, long elapsedMs) {
        EventEmitted event = EventEmitted.from(eventId, apiVersion, timestamp, elapsedMs);
        publisher.publishEvent(event);
        log.debug("[EVENT-PUBLISHED] id={} apiVersion={} elapsedMs={} to Event Receiver API", eventId, apiVersion,
                elapsedMs);
    }
}
