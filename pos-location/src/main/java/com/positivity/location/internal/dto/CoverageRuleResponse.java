package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Response payload for mobile unit coverage rules.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload describing a mobile unit coverage rule")
public class CoverageRuleResponse {

    @Schema(
            description = "Unique identifier of the coverage rule",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID id;

    @Schema(
            description = "Identifier of the mobile unit this rule belongs to",
            example = "01960003-0000-7000-8000-000000000002",
            requiredMode = REQUIRED)
    @NotNull
    private UUID mobileUnitId;

    @Schema(
            description = "Identifier of the service area this rule applies to",
            example = "01960003-0000-7000-8000-000000000003",
            requiredMode = REQUIRED)
    @NotNull
    private UUID serviceAreaId;

    @Schema(
            description = "Type of coverage rule. Recorded only: ruleType itself is not yet evaluated by"
                    + " eligibility, so a DISTANCE_TIER rule matches the same addresses a SERVICE_AREA rule would.",
            example = "SERVICE_AREA",
            allowableValues = {"SERVICE_AREA", "DISTANCE_TIER"},
            requiredMode = NOT_REQUIRED)
    private String ruleType;

    @Schema(
            description = "Evaluation priority of the rule (lower is evaluated first)",
            example = "1",
            requiredMode = NOT_REQUIRED)
    private Integer priority;

    @Schema(
            description = "UTC instant from which the rule is effective, inclusive",
            example = "2026-06-18T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant validFrom;

    @Schema(
            description = "UTC instant until which the rule is effective, exclusive",
            example = "2026-12-31T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant validTo;

    @Schema(
            allOf = DistanceDto.class,
            implementation = Object.class,
            description = "Maximum service distance covered by the rule, in the owning mobile unit's base"
                    + " location's distanceUnit; null for a rule with no distance ceiling. Not yet evaluated:"
                    + " coverage matches on the postal-code service area alone, since geocoding does not exist.",
            requiredMode = NOT_REQUIRED)
    private DistanceDto maxDistance;
}
