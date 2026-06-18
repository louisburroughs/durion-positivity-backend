package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Response payload for controlled workorder reopen")
public class ReopenWorkorderResponse {

    @Schema(
            description = "Workorder identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID workorderId;

    @Schema(description = "Current lifecycle status", example = "WORK_IN_PROGRESS", requiredMode = REQUIRED)
    private String currentStatus;

    @Schema(
            description = "Whether the workorder is reopened for controlled edits",
            example = "true",
            requiredMode = REQUIRED)
    private Boolean isReopened;

    @Schema(description = "Reopen timestamp", example = "2026-01-15T09:30:00Z", requiredMode = REQUIRED)
    private Instant reopenedAt;

    @Schema(description = "Operation message", example = "Workorder reopened successfully", requiredMode = REQUIRED)
    private String message;
}
