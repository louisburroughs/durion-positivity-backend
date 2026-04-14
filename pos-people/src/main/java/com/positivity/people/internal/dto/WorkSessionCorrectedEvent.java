package com.positivity.people.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkSessionCorrectedEvent(
        UUID tenantId,
        UUID originalSessionId,
        UUID correctionId,
        UUID employeeId,
        Instant startTime,
        Instant endTime,
        UUID workOrderId,
        String correctionReason) {}
