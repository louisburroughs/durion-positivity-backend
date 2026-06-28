package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rich filter criteria for the audit event search endpoint (B-3).
 * All fields are optional; an empty filter returns all events paged.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Filter criteria for audit event search; all fields are optional")
public class AuditEventSearchFilter {
    @Schema(
            description = "Inclusive start timestamp (ISO-8601)",
            example = "2026-01-01T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant fromDate;

    @Schema(
            description = "Exclusive end timestamp (ISO-8601)",
            example = "2026-12-31T23:59:59Z",
            requiredMode = NOT_REQUIRED)
    private Instant toDate;

    @Schema(description = "Actor username/service principal", example = "jane.doe", requiredMode = NOT_REQUIRED)
    private String actorId;

    @Schema(
            description = "Workorder UUID - one word per workspace naming policy",
            example = "01960003-0000-7000-8000-000000000030",
            requiredMode = NOT_REQUIRED)
    private UUID workorderId;

    @Schema(
            description = "Movement UUID",
            example = "01960003-0000-7000-8000-000000000031",
            requiredMode = NOT_REQUIRED)
    private UUID movementId;

    @Schema(description = "Product UUID", example = "01960003-0000-7000-8000-000000000032", requiredMode = NOT_REQUIRED)
    private UUID productId;

    @Schema(description = "SKU code", example = "SKU-12345", requiredMode = NOT_REQUIRED)
    private String sku;

    @Schema(description = "Event type code", example = "ROLE_ASSIGNED", requiredMode = NOT_REQUIRED)
    private String eventType;

    @Schema(description = "Aggregate identifier (string)", example = "order-98765", requiredMode = NOT_REQUIRED)
    private String aggregateId;

    @Schema(
            description = "Correlation ID UUID for tracing",
            example = "01960003-0000-7000-8000-000000000033",
            requiredMode = NOT_REQUIRED)
    private UUID correlationId;

    @Schema(description = "Reason code string", example = "MANUAL_ADJUSTMENT", requiredMode = NOT_REQUIRED)
    private String reasonCode;

    @Schema(
            description = "Cursor token for page-based navigation",
            example = "eyJvZmZzZXQiOjEwMH0",
            requiredMode = NOT_REQUIRED)
    private String pageToken;

    @Schema(
            description = "Location UUID list filter",
            example = "[\"01960003-0000-7000-8000-000000000040\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> locationIds;
}
