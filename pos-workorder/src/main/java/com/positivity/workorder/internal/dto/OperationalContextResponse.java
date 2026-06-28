package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Operational context details for a workorder")
public class OperationalContextResponse {
    @Schema(description = "Operational context version", example = "3", requiredMode = NOT_REQUIRED)
    private String version;

    @Schema(
            description = "Location identifier",
            example = "550e8400-e29b-41d4-a716-446655440300",
            requiredMode = NOT_REQUIRED)
    private UUID locationId;

    @Schema(description = "Bay identifier", example = "BAY-12", requiredMode = NOT_REQUIRED)
    private String bayId;

    @Schema(description = "Scheduled start time", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant scheduledStartAt;

    @Schema(description = "Scheduled end time", example = "2026-01-15T09:30:00Z", requiredMode = NOT_REQUIRED)
    private Instant scheduledEndAt;

    @Schema(
            description = "Assigned mechanic identifiers",
            example = "[\"550e8400-e29b-41d4-a716-446655440120\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> assignedMechanics;

    @Schema(
            description = "Assigned resource identifiers",
            example = "[\"550e8400-e29b-41d4-a716-446655440301\"]",
            requiredMode = NOT_REQUIRED)
    private List<UUID> assignedResources;

    @Schema(
            description = "Operational constraints",
            example = "[\"ALIGNMENT_RACK_REQUIRED\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> constraints;

    @Schema(description = "Whether context is locked after work start", example = "false", requiredMode = REQUIRED)
    private boolean locked; // true once workStartedAt is set
}
