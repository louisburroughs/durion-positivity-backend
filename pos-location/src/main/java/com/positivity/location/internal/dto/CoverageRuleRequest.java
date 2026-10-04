package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for mobile unit coverage rules.
 *
 * Issue: #76
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Schema(description = "Request payload defining a coverage rule for a mobile unit")
public class CoverageRuleRequest {

    @Schema(
            description = "Identifier of the service area this rule applies to; must name an existing, active"
                    + " service area (422 SERVICE_AREA_NOT_FOUND when unknown, 422 SERVICE_AREA_INACTIVE when it"
                    + " exists but active is false). Required for every rule type: a DISTANCE_TIER rule is a tier"
                    + " within its service area, and a rule without one never matches an address.",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID serviceAreaId;

    @Schema(
            description = "Type of coverage rule, matched case-insensitively. SERVICE_AREA covers the whole service"
                    + " area; DISTANCE_TIER covers it up to maxDistance, and a unit's DISTANCE_TIER rules must be"
                    + " strictly ascending by maxDistance and end with one rule whose maxDistance is null."
                    + " Recorded and validated but not yet evaluated: eligibility matches on the rule's"
                    + " service-area postal codes, the unit's status and the validity window only.",
            example = "SERVICE_AREA",
            allowableValues = {"SERVICE_AREA", "DISTANCE_TIER"},
            requiredMode = REQUIRED)
    @NotBlank
    private String ruleType;

    @Schema(
            description = "Evaluation priority of the rule (lower is evaluated first)",
            example = "1",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private Integer priority;

    @Schema(
            description = "UTC instant from which the rule is effective, inclusive (DECISION-LOCATION-017)",
            example = "2026-06-18T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant validFrom;

    @Schema(
            description = "UTC instant until which the rule is effective, exclusive; must be after validFrom"
                    + " (DECISION-LOCATION-017)",
            example = "2026-12-31T00:00:00Z",
            requiredMode = NOT_REQUIRED)
    private Instant validTo;

    @Schema(
            implementation = DistanceDto.class,
            description = "Maximum service distance covered by the rule, an explicit {value, unit} object (KM or"
                    + " MI); converted at the edge and stored as kilometres (DECISION-LOCATION-028). A bare number"
                    + " is refused. Not yet evaluated: coverage matches on the postal-code service area alone,"
                    + " since geocoding does not exist.",
            requiredMode = NOT_REQUIRED)
    private Object maxDistance;
}
