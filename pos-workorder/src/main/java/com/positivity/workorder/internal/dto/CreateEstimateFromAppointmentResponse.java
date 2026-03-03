package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response payload for estimate creation from appointment")
public class CreateEstimateFromAppointmentResponse {
    @Schema(description = "Created estimate identifier", example = "550e8400-e29b-41d4-a716-446655440000")
    private UUID estimateId;

    @Schema(description = "Created estimate status", example = "DRAFT")
    private String status;

    @Schema(description = "Whether a new estimate was created (false when idempotency returns existing record)", example = "true")
    private boolean created;
}