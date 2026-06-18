package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Current status of a service bay")
public class BayStatus {
    @Schema(description = "Identifier of the bay", example = "BAY-01", requiredMode = REQUIRED)
    String bayId;

    @Schema(description = "Human-readable bay name", example = "Front Bay 1", requiredMode = NOT_REQUIRED)
    String bayName;

    @Schema(description = "Operational status of the bay", example = "OCCUPIED", requiredMode = REQUIRED)
    String status;

    @Schema(
            description = "Identifier of the workorder currently assigned to the bay",
            example = "550e8400-e29b-41d4-a716-446655440000",
            requiredMode = NOT_REQUIRED)
    String assignedWorkorderId;

    @Schema(description = "Whether the bay is currently available", example = "false", requiredMode = REQUIRED)
    boolean available;
}
