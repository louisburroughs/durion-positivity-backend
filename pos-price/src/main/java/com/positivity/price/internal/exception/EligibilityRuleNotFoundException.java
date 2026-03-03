package com.positivity.price.internal.exception;

/** Thrown when a promotion eligibility rule cannot be found. Issue: #96 */
public class EligibilityRuleNotFoundException extends RuntimeException {
    public EligibilityRuleNotFoundException(java.util.UUID ruleId) {
        super("Eligibility rule not found: " + ruleId);
    }
}
