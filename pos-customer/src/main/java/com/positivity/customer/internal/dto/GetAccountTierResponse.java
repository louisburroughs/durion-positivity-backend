package com.positivity.customer.internal.dto;

import com.positivity.customer.internal.enums.AccountTier;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

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

    @Schema(
            description = "Account/party identifier",
            example = "01960003-0000-7000-8000-000000000001",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private String accountId;

    @Schema(description = "Current tier level", example = "GOLD", requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private AccountTier tier;

    @Schema(description = "Display name of the tier", example = "Gold", requiredMode = Schema.RequiredMode.REQUIRED)
    @NonNull
    private String tierDisplayName;

    @Schema(
            description = "When the tier was last assigned or updated",
            example = "2026-06-17T10:15:30Z",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private Instant tierAssignedAt;

    @Schema(
            description = "Who assigned or last modified the tier",
            example = "user-admin",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private String tierAssignedBy;

    @Schema(
            description = "Optional notes about tier assignment or status",
            example = "Upgraded after Q2 spend review",
            requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    @Nullable
    private String notes;

    @Schema(
            description = "Whether tier was manually assigned or auto-calculated",
            example = "false",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean manualOverride;
}
