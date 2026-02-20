package com.positivity.price.internal.dto;

/**
 * One pricing rule application entry for quote breakdown.
 *
 * Issue: #51
 */
public class PricingBreakdownEntry {

    private String ruleName;
    private String ruleType;
    private MoneyAmount adjustment;
    private MoneyAmount resultingValue;

    public String getRuleName() {
        return ruleName;
    }

    public void setRuleName(String ruleName) {
        this.ruleName = ruleName;
    }

    public String getRuleType() {
        return ruleType;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public MoneyAmount getAdjustment() {
        return adjustment;
    }

    public void setAdjustment(MoneyAmount adjustment) {
        this.adjustment = adjustment;
    }

    public MoneyAmount getResultingValue() {
        return resultingValue;
    }

    public void setResultingValue(MoneyAmount resultingValue) {
        this.resultingValue = resultingValue;
    }
}
