package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Request payload to adjust a travel segment's recorded times")
public class CreateTravelSegmentAdjustmentRequest {

    @Schema(
            description = "Adjusted travel segment start timestamp",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant adjustedStartAt;

    @Schema(
            description = "Adjusted travel segment end timestamp",
            example = "2026-01-15T09:30:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant adjustedEndAt;

    @NotBlank
    @Schema(
            description = "Reason justifying the travel segment time adjustment",
            example = "Corrected start time after GPS sync",
            requiredMode = REQUIRED)
    private String adjustmentReason;
}
