package com.positivity.workorder.internal.dto;

import com.positivity.workorder.internal.enums.FleetAuthorizationResolution;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** An advisor's decision about a fleet payment authorization that did not come back granted (#1346). */
@Schema(description = "An advisor's resolution of a refused or unresolvable fleet payment authorization")
public record FleetAuthorizationResolutionRequest(
        @Schema(description = "What the advisor decided", requiredMode = Schema.RequiredMode.REQUIRED) @NotNull
        FleetAuthorizationResolution resolution,

        @Schema(
                description = "Why, for the audit record",
                example = "Customer approved a reduced scope the fleet will cover",
                requiredMode = Schema.RequiredMode.REQUIRED)
        @NotBlank
        String reason) {}
