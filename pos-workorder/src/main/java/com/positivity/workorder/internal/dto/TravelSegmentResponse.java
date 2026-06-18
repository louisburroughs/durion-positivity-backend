package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.workorder.internal.enums.OnBehalfReasonCode;
import com.positivity.workorder.internal.enums.TravelSegmentStatus;
import com.positivity.workorder.internal.enums.TravelSegmentType;
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
@Schema(description = "Response representation of a travel segment")
public class TravelSegmentResponse {

    @Schema(
            description = "Unique identifier of the travel segment",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID travelSegmentId;

    @Schema(
            description = "Identifier of the mobile work assignment this segment belongs to",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID mobileWorkAssignmentId;

    @Schema(
            description = "Identifier of the technician who performed the travel",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    private UUID technicianId;

    @Schema(description = "Direction or purpose of the travel leg", example = "DEPART_SHOP", requiredMode = REQUIRED)
    private TravelSegmentType segmentType;

    @Schema(
            description = "Timestamp when the travel segment started",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant startAt;

    @Schema(
            description = "Timestamp when the travel segment ended",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant endAt;

    @Schema(
            description = "Identifier of the origin location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID fromLocationId;

    @Schema(
            description = "Identifier of the destination location",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID toLocationId;

    @Schema(
            description = "Identifier of the workorder associated with the travel",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID workOrderId;

    @Schema(description = "Effective travel duration in minutes", example = "2", requiredMode = NOT_REQUIRED)
    private Integer durationMinutes;

    @Schema(description = "Raw recorded travel minutes before buffering", example = "2", requiredMode = NOT_REQUIRED)
    private Integer rawMinutes;

    @Schema(description = "Buffered travel minutes applied by policy", example = "2", requiredMode = NOT_REQUIRED)
    private Integer bufferedMinutes;

    @Schema(description = "Current status of the travel segment", example = "DRAFT", requiredMode = REQUIRED)
    private TravelSegmentStatus status;

    @Schema(
            description = "Identifier of the user who created the segment",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String createdBy;

    @Schema(
            description = "Identifier of the user who last modified the segment",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String lastModifiedBy;

    @Schema(
            description = "Timestamp when the segment was last modified",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant lastModifiedAt;

    @Schema(
            description = "Identifier of the user who acted to record the segment",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private String actedByUserId;

    @Schema(
            description = "Identifier of the person the segment was recorded on behalf of",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID actedForPersonId;

    @Schema(
            description = "Reason code when the segment was entered on behalf of another person",
            example = "TECHNICIAN_UNAVAILABLE",
            requiredMode = NOT_REQUIRED)
    private OnBehalfReasonCode onBehalfReasonCode;

    @Schema(
            description = "Timestamp when the segment was created",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant createdAt;

    @Schema(
            description = "Timestamp when the segment was last updated",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    private Instant updatedAt;
}
