package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Manager override payload for workorder operational context")
public class OperationalContextOverrideRequest {
    @NotNull
    @Schema(description = "Location identifier to set on the workorder", example = "550e8400-e29b-41d4-a716-446655440300")
    private UUID locationId;

    @Schema(description = "Optional bay identifier", example = "BAY-12")
    private String bayId;

    @Schema(description = "Assigned mechanic identifiers", example = "[\"550e8400-e29b-41d4-a716-446655440120\"]")
    private List<UUID> assignedMechanics;

    @Schema(description = "Assigned resource identifiers", example = "[\"550e8400-e29b-41d4-a716-446655440301\"]")
    private List<UUID> assignedResources;

    @Schema(description = "Optional execution constraints", example = "[\"ALIGNMENT_RACK_REQUIRED\"]")
    private List<String> constraints;
}