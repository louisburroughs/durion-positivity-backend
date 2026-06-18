package com.positivity.securityservice.internal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
@Schema(description = "Result of a successful self-registration")
public record SelfRegistrationResponse(
        @Schema(description = "Created user identifier", example = "01960003-0000-7000-8000-000000000001", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID userId,

        @Schema(description = "Resolved or created person identifier", example = "01960003-0000-7000-8000-000000000002", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID personId,

        @Schema(description = "Canonical username assigned to the account", example = "jane.smith", requiredMode = Schema.RequiredMode.REQUIRED)
        String username,

        @Schema(description = "User-person link status", example = "LINKED", requiredMode = Schema.RequiredMode.REQUIRED)
        String linkStatus,

        @Schema(description = "True when an existing person was reused", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean matchedExistingPerson,

        @Nullable @Schema(description = "Summary of CRM person search candidates", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        CrmMatchSummaryDto crmMatchSummary,

        @Nullable @Schema(description = "Idempotency key echoed back when the caller supplied one", example = "5f1d8c2e-1b2a-4c3d-9e8f-0a1b2c3d4e5f", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String idempotencyKey,

        @Schema(description = "False for Phase 1 because follow-up login is required", example = "false", requiredMode = Schema.RequiredMode.REQUIRED)
        boolean issuedTokens) {}
