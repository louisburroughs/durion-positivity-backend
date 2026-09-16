package com.positivity.catalog.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The whole skill requirement declaration for one service (CAP-329). Replace-set: what is sent is
 * what the service requires afterwards. An empty list is a valid declaration — it says the service
 * is <em>unconstrained</em>, which is a different answer from never having been configured.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Replace-set declaration of the skills a service requires; empty declares it unconstrained")
public class ServiceRequirementsRequest {

    @NotNull
    @Valid
    @Schema(description = "Required skills; empty means unconstrained", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<RequiredSkillRequest> requiredSkills;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @Schema(description = "One required skill with the GVWR class range it applies to; both bounds null means ANY")
    public static class RequiredSkillRequest {

        @NotNull
        @Schema(description = "Registry skill id", requiredMode = Schema.RequiredMode.REQUIRED)
        private UUID skillId;

        @Min(1)
        @Max(8)
        @Schema(
                description = "Lowest GVWR class (1-8) the requirement applies to; omit both bounds for ANY",
                example = "4")
        private Integer minGvwrClass;

        @Min(1)
        @Max(8)
        @Schema(
                description = "Highest GVWR class (1-8) the requirement applies to; omit both bounds for ANY",
                example = "8")
        private Integer maxGvwrClass;
    }
}
