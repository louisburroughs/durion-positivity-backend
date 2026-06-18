package com.positivity.price.internal.dto;

import com.positivity.price.internal.enums.ConditionType;
import com.positivity.price.internal.enums.RuleCombination;
import com.positivity.price.internal.enums.RuleOperator;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

/** API request payload to add a promotion eligibility rule. Issue: #96 */
@Schema(description = "Request payload to add a new eligibility rule to a promotion")
public class AddEligibilityRuleRequest {

    @Schema(
            description = "Rule condition type to evaluate",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "ACCOUNT_FLEET_SIZE")
    @NotNull(message = "conditionType is required")
    private ConditionType conditionType;

    @Schema(
            description = "Comparison operator for the rule",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "GREATER_THAN_OR_EQUAL_TO")
    @NotNull(message = "operator is required")
    private RuleOperator operator;

    @Schema(
            description = "Rule threshold or comparison value",
            requiredMode = Schema.RequiredMode.REQUIRED,
            example = "1000")
    @NotBlank(message = "value is required")
    @Size(max = 255, message = "value must not exceed 255 characters")
    private String value;

    @Schema(
            description = "Logical combinator when multiple rules exist",
            example = "AND",
            nullable = true,
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private RuleCombination ruleCombination;

    public ConditionType getConditionType() {
        return conditionType;
    }

    public void setConditionType(ConditionType conditionType) {
        this.conditionType = conditionType;
    }

    public RuleOperator getOperator() {
        return operator;
    }

    public void setOperator(RuleOperator operator) {
        this.operator = operator;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    @Nullable
    public RuleCombination getRuleCombination() {
        return ruleCombination;
    }

    public void setRuleCombination(@Nullable RuleCombination ruleCombination) {
        this.ruleCombination = ruleCombination;
    }
}
