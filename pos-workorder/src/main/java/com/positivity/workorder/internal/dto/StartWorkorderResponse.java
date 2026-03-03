package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload for workorder start transition")
public class StartWorkorderResponse {
    @Schema(description = "Workorder identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID workorderId;

    @Schema(description = "Status prior to start transition", example = "APPROVED")
    private String previousStatus;

    @Schema(description = "Status after start transition", example = "WORK_IN_PROGRESS")
    private String currentStatus;

    @Schema(description = "Transition timestamp", example = "2026-03-02T12:34:56Z")
    private Instant transitionedAt;

    @Schema(description = "Operation outcome message", example = "Work started successfully")
    private String message;
}
