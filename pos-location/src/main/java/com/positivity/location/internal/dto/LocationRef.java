package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lightweight location reference payload for reconciliation roster consumers.
 *
 * Issue: CAP-214 #40
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Lightweight location reference for reconciliation roster consumers")
public class LocationRef {

    @Schema(
            description = "Unique identifier of the location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(
            description = "Display name of the location",
            example = "Downtown Service Center",
            requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(description = "Unique business code of the location", example = "LOC-001", requiredMode = NOT_REQUIRED)
    private String code;

    @Schema(description = "Operational status of the location", example = "ACTIVE", requiredMode = NOT_REQUIRED)
    private String status;

    @Schema(
            description = "Identifier of the location in the HR system of record",
            example = "HR-1001",
            requiredMode = NOT_REQUIRED)
    private String hrLocationId;

    @Schema(
            description = "Timestamp when the location was last updated (ISO 8601)",
            example = "2026-06-18T08:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant updatedAt;
}
