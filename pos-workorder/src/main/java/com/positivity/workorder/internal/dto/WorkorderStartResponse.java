package com.positivity.workorder.internal.dto;

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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response returned after starting work on a workorder")
public class WorkorderStartResponse {

    @Schema(
            description = "Unique identifier of the workorder that was started",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(
            description = "Operational context version for optimistic concurrency",
            example = "2",
            requiredMode = NOT_REQUIRED)
    private String operationalContextVersion;

    @Schema(
            description = "Timestamp when work was started on the workorder",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant workStartedAt;

    @Schema(
            description = "Workorder status prior to the start transition",
            example = "ASSIGNED",
            requiredMode = NOT_REQUIRED)
    private String previousStatus;

    @Schema(
            description = "Workorder status after the start transition",
            example = "WORK_IN_PROGRESS",
            requiredMode = REQUIRED)
    @NotNull
    private String currentStatus;

    @Schema(
            description = "Timestamp when the status transition occurred",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant transitionedAt;

    @Schema(
            description = "Human-readable message describing the start outcome",
            example = "Work started on workorder",
            requiredMode = NOT_REQUIRED)
    private String message;
}
