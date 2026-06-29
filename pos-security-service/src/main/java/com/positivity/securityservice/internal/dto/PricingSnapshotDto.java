package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
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
@Schema(description = "Pricing snapshot including its rule-evaluation trace")
public class PricingSnapshotDto {
    @Schema(
            description = "Snapshot identifier",
            example = "01960003-0000-7000-8000-000000000060",
            requiredMode = REQUIRED)
    UUID snapshotId;

    @Schema(
            description = "Timestamp the snapshot was captured",
            example = "2026-01-15T09:30:00Z",
            requiredMode = REQUIRED)
    Instant timestamp;

    @Schema(
            description = "Serialized quote context evaluated",
            example = "{\"customerId\":\"C-1\"}",
            requiredMode = NOT_REQUIRED)
    String quoteContext;

    @Schema(description = "Final computed price", example = "129.99", requiredMode = REQUIRED)
    BigDecimal finalPrice;

    @Schema(description = "Ordered rule-evaluation steps that produced the price", requiredMode = NOT_REQUIRED)
    List<PricingRuleTraceEntryDto> evaluationSteps;
}
