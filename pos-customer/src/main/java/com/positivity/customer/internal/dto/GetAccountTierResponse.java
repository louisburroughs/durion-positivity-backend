package com.positivity.customer.internal.dto;

import com.positivity.customer.internal.enums.AccountTier;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * Response DTO for retrieving an account's tier level.
 * 
 * Contains the current tier, when it was assigned, and optional
 * metadata about tier benefits or requirements.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Account tier information response")
public class GetAccountTierResponse {

    @Schema(description = "Account/party identifier", example = "123e4567-e89b-12d3-a456-426614174000")
    @NonNull
    private String accountId;

    @Schema(description = "Current tier level", example = "GOLD")
    @NonNull
    private AccountTier tier;

    @Schema(description = "Display name of the tier", example = "Gold")
    @NonNull
    private String tierDisplayName;

    @Schema(description = "When the tier was last assigned or updated")
    @Nullable
    private Instant tierAssignedAt;

    @Schema(description = "Who assigned or last modified the tier")
    @Nullable
    private String tierAssignedBy;

    @Schema(description = "Optional notes about tier assignment or status")
    @Nullable
    private String notes;

    @Schema(description = "Whether tier was manually assigned or auto-calculated")
    private boolean manualOverride;
}
