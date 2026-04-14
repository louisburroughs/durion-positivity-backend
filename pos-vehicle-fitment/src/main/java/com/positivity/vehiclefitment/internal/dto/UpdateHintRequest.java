package com.positivity.vehiclefitment.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
