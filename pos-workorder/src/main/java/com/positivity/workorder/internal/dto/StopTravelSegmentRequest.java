package com.positivity.workorder.internal.dto;

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
    private UUID toLocationId;
    private String notes;
}
