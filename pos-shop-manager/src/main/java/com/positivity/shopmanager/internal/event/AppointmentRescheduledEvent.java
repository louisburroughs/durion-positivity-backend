package com.positivity.shopmanager.internal.event;

import java.time.Instant;
import java.util.UUID;

public record AppointmentRescheduledEvent(
        UUID appointmentId,
        String workorderLinkRef,
        Instant previousStartAt,
        Instant newStartAt,
        String actorId) {
}
