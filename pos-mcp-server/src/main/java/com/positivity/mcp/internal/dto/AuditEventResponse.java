package com.positivity.mcp.internal.dto;

import java.time.Instant;
import java.util.UUID;

public record AuditEventResponse(
        UUID eventId,
        UUID correlationId,
        String eventType,
        Instant timestamp,
        String actorSubjectId,
        String payloadRef) {
}
