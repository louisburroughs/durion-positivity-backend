package com.positivity.shopmanager.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkorderStatusChangedEvent(
        UUID eventId,
        UUID workOrderId,
        String newStatus,
        Instant eventTimestamp,
        UUID correlationId) {
}
