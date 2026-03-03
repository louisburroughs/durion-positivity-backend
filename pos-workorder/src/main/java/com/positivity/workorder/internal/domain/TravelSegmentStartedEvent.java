package com.positivity.workorder.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** Domain event published when a travel segment is started. */
public record TravelSegmentStartedEvent(UUID travelSegmentId, UUID technicianId, Instant startAt) {
}
