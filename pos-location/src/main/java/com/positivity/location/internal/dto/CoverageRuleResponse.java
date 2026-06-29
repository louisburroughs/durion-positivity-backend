package com.positivity.location.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
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

    @Schema(description = "Type of coverage rule", example = "INCLUDE", requiredMode = NOT_REQUIRED)
    private String ruleType;

    @Schema(
            description = "Evaluation priority of the rule (lower is evaluated first)",
            example = "1",
            requiredMode = NOT_REQUIRED)
    private Integer priority;

    @Schema(description = "Date from which the rule is effective", example = "2026-06-18", requiredMode = NOT_REQUIRED)
    private LocalDate validFrom;

    @Schema(description = "Date until which the rule is effective", example = "2026-12-31", requiredMode = NOT_REQUIRED)
    private LocalDate validTo;

    @Schema(
            description = "Maximum service distance in kilometres covered by the rule",
            example = "25.5",
            requiredMode = NOT_REQUIRED)
    private BigDecimal maxDistance;
}
