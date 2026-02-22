package com.positivity.securityservice.internal.dto;

import lombok.Builder;
import lombok.Value;

/**
 * Rule-evaluation entry response payload.
 *
 * Issue: #41
 */
@Value
@Builder
public class PricingRuleTraceEntryDto {
    String id;
    String ruleId;
    String status;
    String inputs;
    String outputs;
}
