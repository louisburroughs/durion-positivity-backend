package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    @Schema(description = "Estimate ID", example = "01960003-0000-7000-8000-000000000001", requiredMode = REQUIRED)
    private UUID estimateId;

    @NotNull(message = "customerId is required")
    @Schema(description = "Customer ID", example = "01960003-0000-7000-8000-000000000002", requiredMode = REQUIRED)
    private UUID customerId;
}
