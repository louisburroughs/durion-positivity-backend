package com.positivity.vehiclefitment.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for updating an existing vehicle applicability hint.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request payload for updating fitment tags on an existing hint")
public class UpdateHintRequest {

    @Schema(description = "Updated fitment tags for the hint", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotEmpty(message = "At least one fitment tag is required")
    @Valid
    private List<FitmentTagDto> fitmentTags;

    @Schema(description = "User or system identity updating the hint", example = "advisor@durion.local")
    @Size(max = 128, message = "updatedBy must not exceed 128 characters")
    private String updatedBy;
}
