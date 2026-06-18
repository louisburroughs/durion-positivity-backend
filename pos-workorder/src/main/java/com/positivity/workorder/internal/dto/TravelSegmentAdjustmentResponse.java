package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
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
@Schema(description = "Response representation of a travel segment time adjustment")
public class TravelSegmentAdjustmentResponse {

    @Schema(
            description = "Unique identifier of the adjustment",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID adjustmentId;

    @Schema(
            description = "Identifier of the travel segment that was adjusted",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID travelSegmentId;

    @Schema(
            description = "Adjusted start timestamp of the travel segment",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant adjustedStartAt;

    @Schema(
            description = "Adjusted end timestamp of the travel segment",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant adjustedEndAt;

    @Schema(
            description = "Reason recorded for the adjustment",
            example = "Corrected for traffic delay not logged automatically",
            requiredMode = NOT_REQUIRED)
    private String adjustmentReason;

    @Schema(
            description = "Identifier of the user who made the adjustment",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String adjustedByUserId;

    @Schema(description = "Approval status of the adjustment", example = "PENDING", requiredMode = NOT_REQUIRED)
    private String approvalStatus;

    @Schema(
            description = "Identifier of the user who approved the adjustment",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String approvedByUserId;

    @Schema(
            description = "Timestamp when the adjustment was approved",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant approvedAt;

    @Schema(
            description = "Timestamp when the adjustment was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant createdAt;
}
