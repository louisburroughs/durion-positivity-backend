package com.positivity.workorder.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** Domain event published when a travel segment is started. */
public record TravelSegmentStartedEvent(UUID travelSegmentId, UUID technicianId, Instant startAt) {

    /** Payload schema version carried on the outbox envelope; bump with the payload shape. */
    public static final int SCHEMA_VERSION = 1;
}
