package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rule-evaluation step provided while creating a pricing snapshot.
 *
 * Issue: #41
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "A rule-evaluation step submitted while creating a pricing snapshot")
public class PricingRuleTraceEntryRequest {
    @NotBlank
    @Schema(description = "Identifier of the evaluated rule", example = "RULE-VOLUME-DISCOUNT", requiredMode = REQUIRED)
    private String ruleId;

    @NotBlank
    @Schema(description = "Evaluation status of the rule", example = "APPLIED", requiredMode = REQUIRED)
    private String status;

    @Schema(description = "Inputs supplied to the rule", requiredMode = NOT_REQUIRED)
    private Object inputs;

    @Schema(description = "Outputs produced by the rule", requiredMode = NOT_REQUIRED)
    private Object outputs;
}
