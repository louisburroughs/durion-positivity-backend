package com.positivity.events;

import java.time.Instant;

/**
 * This event is consumed by the Event Receiver API to store event records.
 * Includes apiVersion to track which API version triggered the event.
 */
public record EventEmitted(
        String eventId,
        String apiVersion,
        long timestamp,
        long elapsedMs,
        Instant publishedAt) {

    /**
     * Create an EventEmitted event.
     *
     * @param eventId    The unique identifier for the event
     * @param apiVersion The API version that triggered the event
     * @param timestamp  The timestamp from the event execution
     * @param elapsedMs  The elapsed time in milliseconds for the operation
     * @param publishedAt The instant the event is published
     * @return EventEmitted domain event
     */
    public static EventEmitted from(
            String eventId,
            String apiVersion,
            long timestamp,
            long elapsedMs,
            Instant publishedAt) {
        return new EventEmitted(eventId, apiVersion, timestamp, elapsedMs, publishedAt);
    }
}
