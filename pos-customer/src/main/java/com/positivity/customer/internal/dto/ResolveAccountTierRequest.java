package com.positivity.customer.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;

/**
 * Request DTO for resolving/computing an account's tier based on business
 * rules.
 * 
 * This allows the system to calculate the appropriate tier based on various
 * criteria like revenue, contract terms, and account history.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Request to compute account tier based on business rules")
public class ResolveAccountTierRequest {

    @Schema(description = "Account/party identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    @NonNull
    private String accountId;

    @Schema(description = "Annual revenue or spending for tier calculation", example = "125000.00")
    @Nullable
    private BigDecimal annualRevenue;

    @Schema(description = "Number of active contracts or subscriptions")
    @Nullable
    private Integer activeContractCount;

    @Schema(description = "Account age in months")
    @Nullable
    private Integer accountAgeMonths;

    @Schema(description = "Whether to apply the resolved tier or just return the recommendation")
    @Builder.Default
    private boolean applyTier = false;

    @Schema(description = "Force recalculation even if tier was manually set")
    @Builder.Default
    private boolean forceRecalculation = false;
}
