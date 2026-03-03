package com.positivity.workorder.internal.dto;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.positivity.workorder.internal.enums.WorkSessionStatus;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response DTO for a work session")
public class WorkSessionResponse {

    @Schema(description = "Unique identifier of the work session")
    private UUID workSessionId;

    @Schema(description = "ID of the mechanic")
    private UUID mechanicId;

    @Schema(description = "ID of the work order")
    private UUID workOrderId;

    @Schema(description = "ID of the work order task")
    private UUID workOrderTaskId;

    @Schema(description = "ID of the work location")
    private UUID locationId;

    @Nullable
    @Schema(description = "Optional resource/bay ID")
    private UUID resourceId;

    @Schema(description = "Session start timestamp")
    private Instant startAt;

    @Nullable
    @Schema(description = "Session end timestamp")
    private Instant endAt;

    @Schema(description = "Current status of the work session")
    private WorkSessionStatus status;

    @Schema(description = "Whether the session is locked")
    private boolean locked;

    @Schema(description = "Total session duration in seconds")
    private int totalDurationSeconds;

    @Nullable
    @Schema(description = "Timestamp when the session was approved")
    private Instant approvedAt;

    @Nullable
    @Schema(description = "User ID of the approver")
    private String approvedByUserId;

    @Nullable
    @Schema(description = "Notes provided at approval")
    private String approvalNotes;

    @Nullable
    @Schema(description = "Timestamp when the session was locked")
    private Instant lockedAt;

    @Schema(description = "Whether an overlap override was used")
    private boolean overlapOverrideUsed;

    @Nullable
    @Schema(description = "Reason provided for the overlap override")
    private String overrideReason;
}
