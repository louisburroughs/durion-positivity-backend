package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for submitting travel segments for a work date")
public class SubmitTravelSegmentsRequest {

    @NotNull
    @Schema(
            description = "Work date whose travel segments are being submitted",
            example = "2026-01-15",
            requiredMode = REQUIRED)
    // reserved: future audit scope query filter; currently not consumed by
    // submitTravelSegments
    private LocalDate workDate;

    @Schema(
            description = "Optional notes accompanying the travel segment submission",
            example = "All segments verified against GPS log",
            requiredMode = NOT_REQUIRED)
    private String notes;
}
