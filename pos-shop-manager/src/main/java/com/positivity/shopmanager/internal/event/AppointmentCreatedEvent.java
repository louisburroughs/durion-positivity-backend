package com.positivity.shopmanager.internal.event;

import java.time.Instant;
import java.util.UUID;

public record AppointmentCreatedEvent(
        UUID appointmentId,
        String crmCustomerId,
        String crmVehicleId,
        Instant startAt,
        Instant endAt,
        String createdBy,
        String workorderLinkRef) {
}
