package com.positivity.securityservice.internal.dto;

import java.math.BigDecimal;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request payload for creating pricing snapshots and rule traces.
 *
 * Issue: #41
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PricingSnapshotRequest {
    private Object quoteContext;
    private BigDecimal finalPrice;
    private List<PricingRuleTraceEntryRequest> evaluationSteps;
}
