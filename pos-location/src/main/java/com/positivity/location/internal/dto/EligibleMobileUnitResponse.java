package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for eligible mobile unit search.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload describing a mobile unit eligible to cover a request")
public class EligibleMobileUnitResponse {

    @Schema(
            description = "Unique identifier of the mobile unit",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(description = "Display name of the mobile unit", example = "Van 7", requiredMode = NOT_REQUIRED)
    private String name;

    @Schema(
            description = "Identifier of the base location the mobile unit operates from",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = NOT_REQUIRED)
    private UUID baseLocationId;

    @Schema(
            description = "Coverage priority of the mobile unit for the requested area (lower is preferred)",
            example = "1",
            requiredMode = NOT_REQUIRED)
    private Integer priority;
}
