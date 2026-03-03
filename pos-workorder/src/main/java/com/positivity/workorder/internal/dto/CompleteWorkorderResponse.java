package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response payload for workorder completion")
public class CompleteWorkorderResponse {
    @Schema(description = "Workorder identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @Schema(description = "Status prior to completion", example = "WORK_IN_PROGRESS")
    private String previousStatus;

    @Schema(description = "Status after completion", example = "COMPLETED")
    private String currentStatus;

    @Schema(description = "Timestamp when completion was recorded", example = "2026-03-02T12:34:56Z")
    private Instant completedAt;

    @Schema(description = "Operation outcome message", example = "Work order completed successfully")
    private String message;
}
