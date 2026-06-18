package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "Manager override payload for workorder operational context")
public class OperationalContextOverrideRequest {
    @NotNull
    @Schema(
            description = "Location identifier to set on the workorder",
            example = "550e8400-e29b-41d4-a716-446655440300",
            requiredMode = REQUIRED)
    private UUID locationId;

    @Schema(description = "Optional bay identifier", example = "BAY-12", requiredMode = NOT_REQUIRED)
    private String bayId;

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
            description = "Optional execution constraints",
            example = "[\"ALIGNMENT_RACK_REQUIRED\"]",
            requiredMode = NOT_REQUIRED)
    private List<String> constraints;
}
