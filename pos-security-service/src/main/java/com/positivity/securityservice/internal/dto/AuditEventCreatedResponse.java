package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Minimal acknowledgement returned after creating an audit event")
public class AuditEventCreatedResponse {
    @Schema(
            description = "Identifier of the created audit event",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    UUID eventId;

    @Schema(
            description = "Timestamp recorded for the audit event",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    Instant timestamp;
}
