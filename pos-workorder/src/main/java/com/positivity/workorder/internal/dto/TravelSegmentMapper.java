package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.entity.TravelSegment;
import com.positivity.workorder.internal.entity.TravelSegmentAdjustment;

public final class TravelSegmentMapper {

    private TravelSegmentMapper() {}

    public static TravelSegmentResponse toResponse(TravelSegment segment) {
        return TravelSegmentResponse.builder()
                .travelSegmentId(segment.getTravelSegmentId())
                .mobileWorkAssignmentId(segment.getMobileWorkAssignmentId())
                .technicianId(segment.getTechnicianId())
                .segmentType(segment.getSegmentType())
                .startAt(segment.getStartAt())
                .endAt(segment.getEndAt())
                .fromLocationId(segment.getFromLocationId())
                .toLocationId(segment.getToLocationId())
                .workOrderId(segment.getWorkOrderId())
                .durationMinutes(segment.getDurationMinutes())
                .rawMinutes(segment.getRawMinutes())
                .bufferedMinutes(segment.getBufferedMinutes())
                .status(segment.getStatus())
                .createdBy(segment.getCreatedBy())
                .lastModifiedBy(segment.getLastModifiedBy())
                .lastModifiedAt(segment.getLastModifiedAt())
                .actedByUserId(segment.getActedByUserId())
                .actedForPersonId(segment.getActedForPersonId())
                .onBehalfReasonCode(segment.getOnBehalfReasonCode())
                .createdAt(segment.getCreatedAt())
                .updatedAt(segment.getUpdatedAt())
                .build();
    }

    public static TravelSegmentAdjustmentResponse toAdjustmentResponse(TravelSegmentAdjustment adjustment) {
        return TravelSegmentAdjustmentResponse.builder()
                .adjustmentId(adjustment.getAdjustmentId())
                .travelSegmentId(adjustment.getTravelSegmentId())
                .adjustedStartAt(adjustment.getAdjustedStartAt())
                .adjustedEndAt(adjustment.getAdjustedEndAt())
                .adjustmentReason(adjustment.getAdjustmentReason())
                .adjustedByUserId(adjustment.getAdjustedByUserId())
                .approvalStatus(adjustment.getApprovalStatus())
                .approvedByUserId(adjustment.getApprovedByUserId())
                .approvedAt(adjustment.getApprovedAt())
                .createdAt(adjustment.getCreatedAt())
                .build();
    }
}
