package com.positivity.securityservice.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Immutable audit event response payload.
 *
 * Issue: #41
 */
@Value
@Builder
public class AuditLogEventDto {
    UUID eventId;
    Instant timestamp;
    String eventType;
    String actorId;
    String entityId;
    String entityType;
    String oldValue;
    String newValue;
    String context;
}
