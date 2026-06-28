package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

@Builder
@Schema(description = "Request to open a self-registration review case")
public record SelfRegistrationReviewCaseCreateRequest(
        @Schema(
                description = "Type of review case",
                example = "IDENTITY_REVIEW",
                requiredMode = Schema.RequiredMode.REQUIRED)
        SelfRegistrationCaseType caseType,

        @Schema(
                description = "Reason code that created the case",
                example = "DUPLICATE_IDENTITY",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reasonCode,

        @Schema(
                description = "Reason message recorded for the case",
                example = "Multiple persons matched the submitted email",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String reasonMessage,

        @Schema(
                description = "Submitted email address",
                example = "jane.smith@example.com",
                requiredMode = Schema.RequiredMode.REQUIRED)
        String email,

        @Nullable
        @Schema(
                description = "Requested username, when available",
                example = "jane.smith",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String requestedUsername,

        @Nullable
        @Schema(
                description = "Resolved or created person identifier, when available",
                example = "01960003-0000-7000-8000-000000000002",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        UUID personId,

        @Nullable
        @Schema(
                description = "Linked or recoverable user identifier, when available",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        UUID linkedUserId,

        @Valid
        @Nullable
        @Schema(description = "CRM candidate summary, when available", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        CrmMatchSummaryDto crmMatchSummary,

        @Nullable
        @Schema(
                description = "Operator notes recorded for the case",
                example = "Escalated for manual identity verification",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String notes) {}
