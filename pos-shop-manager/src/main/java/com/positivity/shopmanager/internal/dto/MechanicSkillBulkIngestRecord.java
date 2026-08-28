package com.positivity.shopmanager.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * One skill held by one mechanic.
 *
 * <p>A file lists a mechanic once per skill, which is how a spreadsheet naturally reads. The
 * endpoint folds the rows by person, because the underlying operation replaces a mechanic's whole
 * skill set rather than adding to it.
 */
@Schema(description = "One mechanic's proficiency in one skill")
public record MechanicSkillBulkIngestRecord(
        @Schema(description = "Canonical person id of the mechanic", example = "018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b")
        @NotBlank(message = "personId is required")
        String personId,

        @Schema(description = "Skill code", example = "T4-BRAKES") @NotBlank(message = "skillCode is required")
        String skillCode,

        @Schema(description = "Proficiency, 1 to 5", example = "4") @Min(1) @Max(5)
        int proficiencyLevel) {}
