package com.positivity.people.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/** One registry skill with the vendor codes that map onto it (CAP-328). */
@Value
@Builder
@Schema(description = "A registry skill: a competence and the GVWR class range it certifies work on")
public class SkillDto {

    @Schema(description = "Registry id", requiredMode = REQUIRED)
    UUID skillId;

    @Schema(description = "Durion skill code", example = "BRAKES-LIGHT", requiredMode = REQUIRED)
    String code;

    @Schema(description = "Display name", example = "Brakes (light duty)", requiredMode = REQUIRED)
    String name;

    @Schema(description = "The competence regardless of duty class", example = "BRAKES", requiredMode = REQUIRED)
    String competenceCode;

    @Schema(description = "Lowest FHWA GVWR class covered (1-8)", example = "1", requiredMode = REQUIRED)
    int minGvwrClass;

    @Schema(description = "Highest FHWA GVWR class covered (1-8)", example = "3", requiredMode = REQUIRED)
    int maxGvwrClass;

    @Schema(
            description = "Vendor codes that map onto this skill, as SOURCE:CODE",
            example = "[\"ASE:A5-BRAKES\"]",
            requiredMode = REQUIRED)
    List<String> sourceCodes;
}
