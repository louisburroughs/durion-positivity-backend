package com.positivity.workorder.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** Domain event published when a travel segment is stopped. */
public record TravelSegmentStoppedEvent(UUID travelSegmentId, UUID technicianId, Instant endAt, int durationMinutes) {

    /** Payload schema version carried on the outbox envelope; bump with the payload shape. */
    public static final int SCHEMA_VERSION = 1;
}
