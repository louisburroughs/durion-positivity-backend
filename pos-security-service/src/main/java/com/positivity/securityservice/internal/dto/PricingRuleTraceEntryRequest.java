package com.positivity.securityservice.internal.dto;

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
public class PricingRuleTraceEntryRequest {
    private String ruleId;
    private String status;
    private Object inputs;
    private Object outputs;
}
