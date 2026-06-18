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
@Schema(description = "Response payload for workorder completion")
public class CompleteWorkorderResponse {
    @Schema(
            description = "Workorder identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID workorderId;

    @Schema(description = "Status prior to completion", example = "WORK_IN_PROGRESS", requiredMode = REQUIRED)
    @NotNull
    private String previousStatus;

    @Schema(description = "Status after completion", example = "COMPLETED", requiredMode = REQUIRED)
    @NotNull
    private String currentStatus;

    @Schema(
            description = "Timestamp when completion was recorded",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    @NotNull
    private Instant completedAt;

    @Schema(
            description = "Operation outcome message",
            example = "Work order completed successfully",
            requiredMode = NOT_REQUIRED)
    private String message;
}
