package com.positivity.workorder.internal.dto;

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
public class TravelSegmentAdjustmentResponse {
    private UUID adjustmentId;
    private UUID travelSegmentId;
    private Instant adjustedStartAt;
    private Instant adjustedEndAt;
    private String adjustmentReason;
    private String adjustedByUserId;
    private String approvalStatus;
    private String approvedByUserId;
    private Instant approvedAt;
    private Instant createdAt;
}
