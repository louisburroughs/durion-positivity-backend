package com.positivity.securityservice.internal.dto;

import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Minimal create response for immutable audit event creation.
 *
 * Issue: #41
 */
@Value
@Builder
public class AuditEventCreatedResponse {
    UUID eventId;
    Instant timestamp;
}
