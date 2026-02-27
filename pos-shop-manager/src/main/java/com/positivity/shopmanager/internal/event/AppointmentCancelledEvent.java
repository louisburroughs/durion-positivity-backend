package com.positivity.shopmanager.internal.event;

import java.util.UUID;

public record AppointmentCancelledEvent(
        UUID appointmentId,
        String workorderLinkRef,
        String cancellationReason,
        String actorId) {
}
