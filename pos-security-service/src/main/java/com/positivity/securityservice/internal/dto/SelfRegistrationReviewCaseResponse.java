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
        @Schema(description = "Review case identifier")
        UUID caseId,
        @Schema(description = "Case type")
        SelfRegistrationCaseType caseType,
        @Schema(description = "Case status")
        SelfRegistrationCaseStatus status,
        @Schema(description = "Reason code that created the case")
        String reasonCode,
        @Schema(description = "Reason message recorded for the case")
        String reasonMessage,
        @Schema(description = "Submitted email address")
        String email,
        @Schema(description = "Requested username, when available")
        String requestedUsername,
        @Schema(description = "Resolved or created person identifier, when available")
        UUID personId,
        @Schema(description = "Linked or recoverable user identifier, when available")
        UUID linkedUserId,
        @Schema(description = "CRM candidate count, when available")
        Integer crmCandidateCount,
        @Schema(description = "CRM shared-identity candidate count, when available")
        Integer crmSharedIdentityCandidateCount,
        @Schema(description = "CRM exact email match flag, when available")
        Boolean crmExactEmailMatch,
        @Schema(description = "CRM exact phone match flag, when available")
        Boolean crmExactPhoneMatch,
        @Schema(description = "CRM exact name match flag, when available")
        Boolean crmExactNameMatch,
        @Schema(description = "Operator notes recorded for the case")
        String notes,
        @Schema(description = "Case creation timestamp")
        Instant createdAt,
        @Schema(description = "Case last-updated timestamp")
        Instant updatedAt,
        @Schema(description = "Resolution timestamp, when resolved")
        Instant resolvedAt,
        @Schema(description = "Actor who resolved the case, when resolved")
        String resolvedBy,
        @Schema(description = "Resolution notes, when resolved")
        String resolutionNotes) {
}
