package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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

    @Schema(
            description = "Account/party identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NonNull
    @NotBlank
    @Size(max = 64)
    private String accountId;

    @Schema(
            description = "Annual revenue or spending for tier calculation",
            example = "125000.00",
            requiredMode = NOT_REQUIRED)
    @Nullable
    @PositiveOrZero
    private BigDecimal annualRevenue;

    @Schema(description = "Number of active contracts or subscriptions", example = "3", requiredMode = NOT_REQUIRED)
    @Nullable
    @PositiveOrZero
    private Integer activeContractCount;

    @Schema(description = "Account age in months", example = "18", requiredMode = NOT_REQUIRED)
    @Nullable
    @PositiveOrZero
    private Integer accountAgeMonths;

    @Schema(
            description = "Whether to apply the resolved tier or just return the recommendation",
            example = "false",
            requiredMode = NOT_REQUIRED)
    @Builder.Default
    private boolean applyTier = false;

    @Schema(
            description = "Force recalculation even if tier was manually set",
            example = "false",
            requiredMode = NOT_REQUIRED)
    @Builder.Default
    private boolean forceRecalculation = false;
}
