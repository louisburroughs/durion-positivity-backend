package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
@Schema(description = "Current availability and status of a mechanic")
public class MechanicStatus {

    @Schema(
            description = "Identifier of the mechanic person",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    String personId;

    @Schema(description = "Mechanic first name", example = "Alex", requiredMode = NOT_REQUIRED)
    String firstName;

    @Schema(description = "Mechanic last name", example = "Rivera", requiredMode = NOT_REQUIRED)
    String lastName;

    @Schema(description = "Current work status of the mechanic", example = "AVAILABLE", requiredMode = NOT_REQUIRED)
    String currentStatus;

    @Schema(
            description = "Identifier of the workorder the mechanic is currently assigned to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    String assignedWorkorderId;

    @Schema(description = "Whether the mechanic is currently on break", example = "false", requiredMode = NOT_REQUIRED)
    boolean onBreak;

    @Schema(
            description = "Expected return time when the mechanic is on break",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    Instant breakExpectedReturn;

    @Schema(description = "Paid time-off entries for the mechanic", requiredMode = NOT_REQUIRED)
    List<PtoEntry> ptoEntries;
}
