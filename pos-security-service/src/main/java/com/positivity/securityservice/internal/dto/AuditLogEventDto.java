package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Immutable audit event record")
public class AuditLogEventDto {
    @Schema(
            description = "Audit event identifier",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = REQUIRED)
    UUID eventId;

    @Schema(description = "Timestamp recorded for the event", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    Instant timestamp;

    @Schema(description = "Event type code", example = "ROLE_ASSIGNED", requiredMode = REQUIRED)
    String eventType;

    @Schema(description = "Actor that triggered the event", example = "jane.doe", requiredMode = NOT_REQUIRED)
    String actorId;

    @Schema(
            description = "Identifier of the affected entity",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    String entityId;

    @Schema(description = "Type of the affected entity", example = "USER", requiredMode = NOT_REQUIRED)
    String entityType;

    @Schema(
            description = "Serialized value before the change",
            example = "{\"roles\":[\"CLERK\"]}",
            requiredMode = NOT_REQUIRED)
    String oldValue;

    @Schema(
            description = "Serialized value after the change",
            example = "{\"roles\":[\"SHOP_MGR\"]}",
            requiredMode = NOT_REQUIRED)
    String newValue;

    @Schema(
            description = "Additional contextual metadata",
            example = "{\"ip\":\"10.0.0.1\"}",
            requiredMode = NOT_REQUIRED)
    String context;
}
