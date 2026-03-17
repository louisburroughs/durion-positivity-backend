package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
@Schema(description = "Result of a successful self-registration")
public record SelfRegistrationResponse(
        @Schema(description = "Created user identifier")
        UUID userId,
        @Schema(description = "Resolved or created person identifier")
        UUID personId,
        @Schema(description = "Canonical username assigned to the account")
        String username,
        @Schema(description = "User-person link status", example = "LINKED")
        String linkStatus,
        @Schema(description = "True when an existing person was reused")
        boolean matchedExistingPerson,
        @Nullable
        @Schema(description = "Summary of CRM person search candidates")
        CrmMatchSummaryDto crmMatchSummary,
        @Nullable
        @Schema(description = "Idempotency key echoed back when the caller supplied one")
        String idempotencyKey,
        @Schema(description = "False for Phase 1 because follow-up login is required")
        boolean issuedTokens) {
}
