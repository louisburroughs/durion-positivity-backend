package com.positivity.events;

import java.time.Instant;

/**
 * Domain event published when an @EmitEvent annotated method executes.
 * This event is consumed by the Event Receiver API to store event records.
 */
public record EventEmitted(
        String eventId,
        long timestamp,
        Instant publishedAt) {

    /**
     * Create an EventEmitted event.
     *
     * @param eventId   The event identifier from @EmitEvent annotation
     * @param timestamp The timestamp from the event execution
     * @return EventEmitted domain event
     */
    public static EventEmitted from(String eventId, long timestamp) {
        return new EventEmitted(eventId, timestamp, Instant.now());
    }
}
