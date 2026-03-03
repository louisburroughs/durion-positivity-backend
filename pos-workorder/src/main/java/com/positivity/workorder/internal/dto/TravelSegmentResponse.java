package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.enums.OnBehalfReasonCode;
import com.positivity.workorder.internal.enums.TravelSegmentStatus;
import com.positivity.workorder.internal.enums.TravelSegmentType;
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
@Schema(description = "Response representation of a travel segment")
public class TravelSegmentResponse {
    private UUID travelSegmentId;
    private UUID mobileWorkAssignmentId;
    private UUID technicianId;
    private TravelSegmentType segmentType;
    private Instant startAt;
    private Instant endAt;
    private UUID fromLocationId;
    private UUID toLocationId;
    private UUID workOrderId;
    private Integer durationMinutes;
    private Integer rawMinutes;
    private Integer bufferedMinutes;
    private TravelSegmentStatus status;
    private String createdBy;
    private String lastModifiedBy;
    private Instant lastModifiedAt;
    private String actedByUserId;
    private UUID actedForPersonId;
    private OnBehalfReasonCode onBehalfReasonCode;
    private Instant createdAt;
    private Instant updatedAt;
}
