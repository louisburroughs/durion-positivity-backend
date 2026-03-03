package com.positivity.workorder.internal.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

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
