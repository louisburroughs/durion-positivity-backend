package com.positivity.vehiclefitment.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for creating a new vehicle applicability hint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for creating a vehicle applicability hint")
public class CreateHintRequest {

    @Schema(
            description = "Product identifier for the hint",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "550e8400-e29b-41d4-a716-446655440000")
    @NotNull(message = "Product ID is required")
    private UUID productId;

    @Schema(
            description = "Fitment tags used to determine product applicability",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "At least one fitment tag is required")
    @Valid
    private List<FitmentTagDto> fitmentTags;
}
