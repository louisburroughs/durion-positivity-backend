package com.positivity.people.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record WorkSessionCompletedEvent(
        UUID tenantId, UUID sessionId, UUID employeeId, Instant startTime, Instant endTime, UUID workOrderId) {}
