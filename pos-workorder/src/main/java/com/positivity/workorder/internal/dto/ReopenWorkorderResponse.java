package com.positivity.workorder.internal.dto;

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

    @Schema(description = "Workorder identifier")
    private UUID workorderId;

    @Schema(description = "Current lifecycle status")
    private String currentStatus;

    @Schema(description = "Whether the workorder is reopened for controlled edits")
    private Boolean isReopened;

    @Schema(description = "Reopen timestamp")
    private Instant reopenedAt;

    @Schema(description = "Operation message")
    private String message;
}
