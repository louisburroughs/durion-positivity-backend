package com.positivity.shopmanager.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

/**
 * Request body for the operator skills replace-set on a mechanic.
 */
public record ReplaceMechanicSkillsRequest(
        @Schema(description = "Full replacement skill set for the mechanic") @NotEmpty
        List<@Valid SkillInput> skills) {

    public record SkillInput(
            @Schema(description = "Skill code (ASE T-series convention)", example = "T4-BRAKES") @NotBlank
            String skillCode,

            @Schema(description = "Proficiency 1 (beginner) to 5 (master)", example = "4") @Min(1) @Max(5)
            int proficiencyLevel) {}
}
