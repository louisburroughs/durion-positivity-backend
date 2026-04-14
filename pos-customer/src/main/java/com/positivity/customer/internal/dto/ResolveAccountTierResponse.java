package com.positivity.customer.internal.dto;

import com.positivity.customer.internal.enums.AccountTier;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Response DTO for account tier resolution/calculation.
 *
 * Contains the recommended tier based on business rules and whether
 * it was applied to the account.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Account tier resolution result")
public class ResolveAccountTierResponse {

    @Schema(description = "Account/party identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    @NonNull
    private String accountId;

    @Schema(description = "Currently assigned tier (before resolution)", example = "SILVER")
    @NonNull
    private AccountTier currentTier;

    @Schema(description = "Recommended/resolved tier based on business rules", example = "GOLD")
    @NonNull
    private AccountTier recommendedTier;

    @Schema(description = "Whether the tier was applied to the account")
    private boolean tierApplied;

    @Schema(description = "Whether the current tier was manually set and blocked auto-update")
    private boolean manualOverrideActive;

    @Schema(description = "Explanation of why this tier was recommended")
    @Nullable
    private String resolutionReason;

    @Schema(description = "Score or metric used for tier calculation")
    @Nullable
    private Integer tierScore;
}
