package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request body for stopping an in-progress travel segment")
public class StopTravelSegmentRequest {

    @Schema(
            description = "Identifier of the location reached when the travel segment stops",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = NOT_REQUIRED)
    private UUID toLocationId;

    @Schema(
            description = "Optional notes recorded when stopping the travel segment",
            example = "Arrived at customer site, parking on south lot",
            requiredMode = NOT_REQUIRED)
    private String notes;
}
