package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Audit event record describing a single recorded action within the MCP server")
public record AuditEventResponse(
        @Schema(
                        description = "Unique identifier of the audit event",
                        example = "01960003-0000-7000-8000-000000000001",
                        requiredMode = REQUIRED)
                @NotNull
                UUID eventId,
        @Schema(
                        description = "Correlation identifier linking the event to a request flow",
                        example = "01960003-0000-7000-8000-000000000002",
                        requiredMode = NOT_REQUIRED)
                UUID correlationId,
        @Schema(
                        description = "Type of the audit event",
                        example = "NLTI_INTENT_RESOLVED",
                        requiredMode = REQUIRED)
                @NotNull
                String eventType,
        @Schema(
                        description = "Timestamp when the event occurred (ISO 8601)",
                        example = "2026-01-15T09:30:00Z",
                        requiredMode = REQUIRED)
                @NotNull
                Instant timestamp,
        @Schema(
                        description = "Subject identifier of the actor that triggered the event",
                        example = "user-01960003",
                        requiredMode = NOT_REQUIRED)
                String actorSubjectId,
        @Schema(
                        description = "Reference pointer to the stored event payload",
                        example = "payload/01960003-0000-7000-8000-000000000003",
                        requiredMode = NOT_REQUIRED)
                String payloadRef) {}
