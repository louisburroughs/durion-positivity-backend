package com.positivity.mcp.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Query filter criteria for retrieving audit events")
public record AuditQuery(
        @Schema(
                description = "Correlation identifier to filter audit events by request flow",
                example = "01960003-0000-7000-8000-000000000002",
                requiredMode = NOT_REQUIRED)
        UUID correlationId,

        @Schema(
                description = "Lower bound of the event timestamp range, inclusive (ISO 8601)",
                example = "2026-01-01T00:00:00Z",
                requiredMode = NOT_REQUIRED)
        Instant from,

        @Schema(
                description = "Upper bound of the event timestamp range, inclusive (ISO 8601)",
                example = "2026-01-31T23:59:59Z",
                requiredMode = NOT_REQUIRED)
        Instant to,

        @Schema(
                description = "Event type to filter audit events by",
                example = "NLTI_INTENT_RESOLVED",
                requiredMode = NOT_REQUIRED)
        String eventType) {}
