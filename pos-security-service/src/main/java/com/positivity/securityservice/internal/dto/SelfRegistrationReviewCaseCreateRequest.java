package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.enums.SelfRegistrationCaseType;
import java.util.UUID;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

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
        @Nullable String notes) {}
