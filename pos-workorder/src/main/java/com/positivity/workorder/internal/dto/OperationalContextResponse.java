package com.positivity.workorder.internal.dto;

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
    @Schema(description = "Operational context version", example = "3")
    private String version;

    @Schema(description = "Location identifier", example = "550e8400-e29b-41d4-a716-446655440300")
    private UUID locationId;

    @Schema(description = "Bay identifier", example = "BAY-12")
    private String bayId;

    @Schema(description = "Scheduled start time")
    private Instant scheduledStartAt;

    @Schema(description = "Scheduled end time")
    private Instant scheduledEndAt;

    @Schema(description = "Assigned mechanic identifiers", example = "[\"550e8400-e29b-41d4-a716-446655440120\"]")
    private List<UUID> assignedMechanics;

    @Schema(description = "Assigned resource identifiers", example = "[\"550e8400-e29b-41d4-a716-446655440301\"]")
    private List<UUID> assignedResources;

    @Schema(description = "Operational constraints", example = "[\"ALIGNMENT_RACK_REQUIRED\"]")
    private List<String> constraints;

    @Schema(description = "Whether context is locked after work start", example = "false")
    private boolean locked; // true once workStartedAt is set
}
