package com.positivity.workorder.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

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
@Schema(description = "Response payload for estimate creation from appointment")
public class CreateEstimateFromAppointmentResponse {
    @Schema(
            description = "Created estimate identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID estimateId;

    @Schema(description = "Created estimate status", example = "DRAFT", requiredMode = REQUIRED)
    @NotNull
    private String status;

    @Schema(
            description = "Whether a new estimate was created (false when idempotency returns existing record)",
            example = "true",
            requiredMode = REQUIRED)
    private boolean created;
}
