package com.positivity.securityservice.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
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
@Schema(description = "Request to create a pricing snapshot with its rule-evaluation trace")
public class PricingSnapshotRequest {
    @Schema(description = "Quote context evaluated to produce the price", requiredMode = NOT_REQUIRED)
    private Object quoteContext;

    @NotNull
    @Schema(description = "Final computed price", example = "129.99", requiredMode = REQUIRED)
    private BigDecimal finalPrice;

    @Valid
    @Schema(description = "Ordered rule-evaluation steps that produced the price", requiredMode = NOT_REQUIRED)
    private List<PricingRuleTraceEntryRequest> evaluationSteps;
}
