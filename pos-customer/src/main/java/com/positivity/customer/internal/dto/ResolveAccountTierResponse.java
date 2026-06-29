package com.positivity.customer.internal.dto;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

import com.positivity.customer.internal.enums.AccountTier;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

    @Schema(
            description = "Account/party identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = REQUIRED)
    @NonNull
    @NotBlank
    private String accountId;

    @Schema(description = "Currently assigned tier (before resolution)", example = "SILVER", requiredMode = REQUIRED)
    @NonNull
    @NotNull
    private AccountTier currentTier;

    @Schema(
            description = "Recommended/resolved tier based on business rules",
            example = "GOLD",
            requiredMode = REQUIRED)
    @NonNull
    @NotNull
    private AccountTier recommendedTier;

    @Schema(description = "Whether the tier was applied to the account", example = "true", requiredMode = REQUIRED)
    private boolean tierApplied;

    @Schema(
            description = "Whether the current tier was manually set and blocked auto-update",
            example = "false",
            requiredMode = REQUIRED)
    private boolean manualOverrideActive;

    @Schema(
            description = "Explanation of why this tier was recommended",
            example = "Annual revenue exceeds GOLD threshold of 100000.00",
            requiredMode = NOT_REQUIRED)
    @Nullable
    private String resolutionReason;

    @Schema(description = "Score or metric used for tier calculation", example = "82", requiredMode = NOT_REQUIRED)
    @Nullable
    private Integer tierScore;
}
