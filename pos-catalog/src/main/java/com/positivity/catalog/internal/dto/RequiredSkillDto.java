package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** One skill a service requires, per GVWR class range (CAP-329); the read-side projection. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A skill the service requires, conditioned on the vehicle's GVWR class")
public class RequiredSkillDto {

    @Schema(description = "Registry skill id", example = "01960011-0000-7000-8000-000000000041")
    private UUID skillId;

    @Schema(description = "Registry skill code", example = "BRAKES-MEDIUM_HEAVY")
    private String skillCode;

    @Schema(description = "Competence the skill certifies", example = "BRAKES")
    private String competenceCode;

    @Schema(
            description =
                    "Lowest GVWR class (1-8) the requirement applies to; null with maxGvwrClass null means ANY class",
            example = "4")
    private Integer minGvwrClass;

    @Schema(
            description = "Highest GVWR class (1-8) the requirement applies to; null with minGvwrClass null means ANY",
            example = "8")
    private Integer maxGvwrClass;

    @Schema(
            description = "False when the registry has since retired the skill; the requirement still names it",
            example = "true")
    private boolean skillActive;
}
