package com.positivity.workorder.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * DTO for creating workorders.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request DTO for workorder creation")
public class CreateWorkorderRequest {

    @NotNull(message = "estimateId is required")
    @Schema(description = "Estimate ID", example = "550e8400-e29b-41d4-a716-446655440001")
    private UUID estimateId;

    @NotNull(message = "customerId is required")
    @Schema(description = "Customer ID", example = "550e8400-e29b-41d4-a716-446655440002")
    private UUID customerId;
}
