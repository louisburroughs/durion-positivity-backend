package com.positivity.vehiclefitment.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for vehicle applicability hint response.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Response payload for a vehicle applicability hint")
public class HintResponse {
    @Schema(
            description = "Hint identifier",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440100")
    @NotNull
    private String hintId;

    @Schema(
            description = "Product identifier tied to the hint",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull
    private String productId;

    @Schema(description = "Fitment tags for this hint", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull
    private List<FitmentTagDto> fitmentTags;

    @Schema(
            description = "Hint creation timestamp",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-03-05T14:10:00")
    @NotNull
    private LocalDateTime createdAt;

    @Schema(
            description = "Hint last update timestamp",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "2026-03-05T14:12:00")
    @NotNull
    private LocalDateTime updatedAt;

    @Schema(description = "User or system identity that created the hint", example = "tech.ops@durion.local")
    private String createdBy;

    @Schema(description = "User or system identity that last updated the hint", example = "advisor@durion.local")
    private String updatedBy;
}
