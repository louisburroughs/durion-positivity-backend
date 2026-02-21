package com.positivity.securityservice.internal.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.Builder;
import lombok.Value;

/**
 * Pricing snapshot response with rule-evaluation trace entries.
 *
 * Issue: #41
 */
@Value
@Builder
public class PricingSnapshotDto {
    UUID snapshotId;
    Instant timestamp;
    String quoteContext;
    BigDecimal finalPrice;
    List<PricingRuleTraceEntryDto> evaluationSteps;
}
