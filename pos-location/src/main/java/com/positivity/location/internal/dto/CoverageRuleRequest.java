package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.LocalDate;
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
            description = "Identifier of the service area this rule applies to; must name an existing service area"
                    + " (422 SERVICE_AREA_NOT_FOUND otherwise). Required for every rule type: a DISTANCE_TIER rule"
                    + " is a tier within its service area, and a rule without one never matches an address.",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NotNull
    private UUID serviceAreaId;

    @Schema(
            description = "Type of coverage rule, matched case-insensitively. SERVICE_AREA covers the whole service"
                    + " area; DISTANCE_TIER covers it up to maxDistance, and a unit's DISTANCE_TIER rules must be"
                    + " strictly ascending by maxDistance and end with one rule whose maxDistance is null.",
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

    @Schema(description = "Date from which the rule is effective", example = "2026-06-18", requiredMode = NOT_REQUIRED)
    private LocalDate validFrom;

    @Schema(
            description = "Date until which the rule is effective; must not be before validFrom",
            example = "2026-12-31",
            requiredMode = NOT_REQUIRED)
    private LocalDate validTo;

    @Schema(
            description = "Maximum service distance in kilometres covered by the rule",
            example = "25.5",
            requiredMode = NOT_REQUIRED)
    @PositiveOrZero
    private BigDecimal maxDistance;
}
