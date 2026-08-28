package com.positivity.securityservice.internal.dto;

import com.positivity.securityservice.internal.enums.SelfRegistrationAttemptStatus;
import java.util.UUID;
import lombok.Builder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Read-only projection of a persisted self-registration attempt, used to replay an idempotent
 * outcome without exposing the {@code SelfRegistrationAttempt} JPA entity across the service seam.
 */
@Builder
public record SelfRegistrationAttemptSnapshot(
        @NonNull String idempotencyKey,
        @NonNull String requestFingerprint,
        @Nullable String username,
        @NonNull SelfRegistrationAttemptStatus status,
        @Nullable UUID userId,
        @Nullable UUID personId,
        @Nullable String linkStatus,
        boolean matchedExistingPerson,
        boolean issuedTokens,
        @Nullable Integer crmCandidateCount,
        @Nullable Boolean crmAnyMatches,
        @Nullable Integer crmIndividualCustomerCandidateCount,
        @Nullable Integer crmCommercialContactCandidateCount,
        @Nullable Integer crmSharedIdentityCandidateCount,
        @Nullable Boolean crmExactEmailMatch,
        @Nullable Boolean crmExactPhoneMatch,
        @Nullable Boolean crmExactNameMatch,
        @Nullable Boolean crmReviewRequired,
        @Nullable String conflictCode,
        @Nullable String conflictMessage,
        @Nullable UUID referenceId) {}
