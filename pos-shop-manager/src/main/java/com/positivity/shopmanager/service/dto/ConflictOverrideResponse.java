package com.positivity.shopmanager.service.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** Response returned after a successful conflict override. */
@Value
@Builder
@Schema(description = "Response returned after a successful scheduling conflict override")
public class ConflictOverrideResponse {
    @Schema(
            description = "Unique override record identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    UUID overrideId;

    @Schema(
            description = "Appointment identifier whose conflict was overridden",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    UUID appointmentId;

    @Schema(
            description = "User identifier of the manager who performed the override",
            example = "01960003-0000-7000-8000-000000000020",
            requiredMode = REQUIRED)
    String overriddenByUserId;

    @Schema(
            description = "Instant the override was performed in UTC (ISO-8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = REQUIRED)
    Instant overrideTimestamp;

    @Schema(
            description = "Reason recorded for the override",
            example = "Customer waiting on-site",
            requiredMode = REQUIRED)
    String overrideReason;
}
