package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.enums.SelfRegistrationCaseStatus;
import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;
import lombok.Builder;

@Builder
@Schema(description = "Administrative view of a blocked self-registration case")
public record SelfRegistrationReviewCaseResponse(
        @Schema(
                description = "Review case identifier",
                example = "01960003-0000-7000-8000-000000000070",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID caseId,

        @Schema(description = "Case type", example = "IDENTITY_REVIEW", requiredMode = Schema.RequiredMode.REQUIRED)
        SelfRegistrationCaseType caseType,

        @Schema(description = "Case status", example = "OPEN", requiredMode = Schema.RequiredMode.REQUIRED)
        SelfRegistrationCaseStatus status,

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

        @Schema(
                description = "Requested username, when available",
                example = "jane.smith",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String requestedUsername,

        @Schema(
                description = "Resolved or created person identifier, when available",
                example = "01960003-0000-7000-8000-000000000002",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        UUID personId,

        @Schema(
                description = "Linked or recoverable user identifier, when available",
                example = "01960003-0000-7000-8000-000000000001",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        UUID linkedUserId,

        @Schema(
                description = "CRM candidate count, when available",
                example = "3",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer crmCandidateCount,

        @Schema(
                description = "CRM shared-identity candidate count, when available",
                example = "0",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Integer crmSharedIdentityCandidateCount,

        @Schema(
                description = "CRM exact email match flag, when available",
                example = "true",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean crmExactEmailMatch,

        @Schema(
                description = "CRM exact phone match flag, when available",
                example = "false",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean crmExactPhoneMatch,

        @Schema(
                description = "CRM exact name match flag, when available",
                example = "false",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Boolean crmExactNameMatch,

        @Schema(
                description = "Operator notes recorded for the case",
                example = "Escalated for manual identity verification",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String notes,

        @Schema(
                description = "Case creation timestamp",
                example = "2026-01-15T09:30:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant createdAt,

        @Schema(
                description = "Case last-updated timestamp",
                example = "2026-01-16T11:00:00Z",
                requiredMode = Schema.RequiredMode.REQUIRED)
        Instant updatedAt,

        @Schema(
                description = "Resolution timestamp, when resolved",
                example = "2026-01-17T14:00:00Z",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        Instant resolvedAt,

        @Schema(
                description = "Actor who resolved the case, when resolved",
                example = "admin",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String resolvedBy,

        @Schema(
                description = "Resolution notes, when resolved",
                example = "Recovered existing disabled account",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED)
        String resolutionNotes) {}
