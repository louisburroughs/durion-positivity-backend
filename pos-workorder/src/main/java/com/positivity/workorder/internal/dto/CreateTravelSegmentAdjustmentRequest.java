package com.positivity.workorder.internal.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateTravelSegmentAdjustmentRequest {
    private Instant adjustedStartAt;
    private Instant adjustedEndAt;

    @NotBlank
    private String adjustmentReason;
}
