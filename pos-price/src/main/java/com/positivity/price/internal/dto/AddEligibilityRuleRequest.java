package com.positivity.price.internal.dto;

import com.positivity.price.internal.enums.ConditionType;
import com.positivity.price.internal.enums.RuleCombination;
import com.positivity.price.internal.enums.RuleOperator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.jspecify.annotations.Nullable;

/** API request payload to add a promotion eligibility rule. Issue: #96 */
public class AddEligibilityRuleRequest {

    @NotNull
    private ConditionType conditionType;

    @NotNull
    private RuleOperator operator;

    @NotBlank
    private String value;

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
