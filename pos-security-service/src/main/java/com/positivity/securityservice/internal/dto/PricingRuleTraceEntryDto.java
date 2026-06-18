package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Value;

/**
 * Rule-evaluation entry response payload.
 *
 * Issue: #41
 */
@Value
@Builder
@Schema(description = "A single rule-evaluation step recorded in a pricing snapshot trace")
public class PricingRuleTraceEntryDto {
    @Schema(description = "Trace entry identifier", example = "01960003-0000-7000-8000-000000000050", requiredMode = REQUIRED)
    String id;

    @Schema(description = "Identifier of the evaluated rule", example = "RULE-VOLUME-DISCOUNT", requiredMode = REQUIRED)
    String ruleId;

    @Schema(description = "Evaluation status of the rule", example = "APPLIED", requiredMode = REQUIRED)
    String status;

    @Schema(description = "Serialized inputs supplied to the rule", example = "{\"qty\":10}", requiredMode = NOT_REQUIRED)
    String inputs;

    @Schema(description = "Serialized outputs produced by the rule", example = "{\"discount\":0.05}", requiredMode = NOT_REQUIRED)
    String outputs;
}
