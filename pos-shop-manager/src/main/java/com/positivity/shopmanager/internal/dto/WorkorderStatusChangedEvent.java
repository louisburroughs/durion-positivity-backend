package com.positivity.shopmanager.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

/** Event published when a workorder transitions to a new status. */
@Schema(description = "Event published when a workorder transitions to a new status")
public record WorkorderStatusChangedEvent(
        @Schema(
                description = "Unique event identifier",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = REQUIRED)
        UUID eventId,

        @Schema(
                description = "Workorder identifier whose status changed",
                example = "01960003-0000-7000-8000-000000000002",
                requiredMode = REQUIRED)
        UUID workOrderId,

        @Schema(description = "New workorder status", example = "IN_PROGRESS", requiredMode = REQUIRED)
        String newStatus,

        @Schema(
                description = "Instant the status change occurred in UTC (ISO-8601)",
                example = "2026-06-18T08:00:00Z",
                requiredMode = REQUIRED)
        Instant eventTimestamp,

        @Schema(
                description = "Correlation identifier propagated from the originating request",
                example = "01960003-0000-7000-8000-0000000000ff",
                requiredMode = REQUIRED)
        UUID correlationId) {}
