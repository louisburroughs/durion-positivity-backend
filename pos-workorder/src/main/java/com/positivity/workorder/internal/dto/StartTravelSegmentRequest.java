package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.enums.OnBehalfReasonCode;
import com.positivity.workorder.internal.enums.TravelSegmentType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request body for starting a travel segment")
public class StartTravelSegmentRequest {

    @NotNull
    private UUID mobileWorkAssignmentId;

    @NotNull
    private UUID technicianId;

    @NotNull
    private TravelSegmentType segmentType;

    private UUID fromLocationId;
    private UUID toLocationId;
    private UUID workOrderId;
    private UUID actedForPersonId;
    private OnBehalfReasonCode onBehalfReasonCode;
}
