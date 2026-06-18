package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.TimeEntryStatus;
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
@Schema(description = "Response representation of a time entry")
public class TimeEntryResponse {

    @Schema(
            description = "Unique identifier of the time entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID timeEntryId;

    @Schema(
            description = "Identifier of the person the time entry belongs to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID personId;

    @Schema(
            description = "Identifier of the workorder associated with the time entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID workOrderId;

    @Schema(
            description = "Timestamp when the time entry started",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant startAt;

    @Schema(
            description = "Timestamp when the time entry ended",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant endAt;

    @Schema(description = "Current status of the time entry", example = "SUBMITTED", requiredMode = REQUIRED)
    private TimeEntryStatus status;

    @Schema(
            description = "Timestamp when the time entry was submitted",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant submittedAt;

    @Schema(
            description = "Identifier of the user who approved or rejected the entry",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String decisionByUserId;

    @Schema(
            description = "UTC timestamp when the approval decision was made",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant decisionAtUtc;

    @Schema(
            description = "Reason supplied when the time entry was rejected",
            example = "Hours exceed scheduled shift",
            requiredMode = NOT_REQUIRED)
    private String rejectionReason;

    @Schema(
            description = "Timestamp when the time entry was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the time entry was last updated",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant updatedAt;
}
