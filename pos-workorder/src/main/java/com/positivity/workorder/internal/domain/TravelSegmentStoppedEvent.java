package com.positivity.workorder.internal.domain;

import java.time.Instant;
import java.util.UUID;

/** Domain event published when a travel segment is stopped. */
public record TravelSegmentStoppedEvent(UUID travelSegmentId, UUID technicianId, Instant endAt, int durationMinutes) {
}
