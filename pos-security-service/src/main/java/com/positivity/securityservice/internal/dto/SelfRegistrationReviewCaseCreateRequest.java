package com.positivity.securityservice.internal.dto;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;

import lombok.Builder;

@Builder
public record SelfRegistrationReviewCaseCreateRequest(
                SelfRegistrationCaseType caseType,
                String reasonCode,
                String reasonMessage,
                String email,
                @Nullable String requestedUsername,
                @Nullable UUID personId,
                @Nullable UUID linkedUserId,
                @Nullable CrmMatchSummaryDto crmMatchSummary,
                @Nullable String notes) {
}
