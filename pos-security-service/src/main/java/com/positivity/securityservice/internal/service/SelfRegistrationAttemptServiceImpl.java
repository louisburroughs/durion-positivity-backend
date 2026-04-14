package com.positivity.securityservice.internal.service;

import com.positivity.securityservice.internal.dto.CrmMatchSummaryDto;
import com.positivity.securityservice.internal.dto.SelfRegistrationResponse;
import com.positivity.securityservice.internal.entity.SelfRegistrationAttempt;
import com.positivity.securityservice.internal.enums.SelfRegistrationAttemptStatus;
import com.positivity.securityservice.internal.repository.SelfRegistrationAttemptRepository;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SelfRegistrationAttemptServiceImpl implements SelfRegistrationAttemptService {

    private final SelfRegistrationAttemptRepository selfRegistrationAttemptRepository;

    @Override
    @Transactional(readOnly = true)
    public @NonNull Optional<SelfRegistrationAttempt> findByIdempotencyKey(@NonNull String idempotencyKey) {
        return selfRegistrationAttemptRepository.findByIdempotencyKey(idempotencyKey);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSuccess(
            @NonNull String idempotencyKey,
            @NonNull String requestFingerprint,
            @NonNull String email,
            @Nullable String username,
            @NonNull SelfRegistrationResponse response) {
        SelfRegistrationAttempt attempt = selfRegistrationAttemptRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElseGet(SelfRegistrationAttempt::new);
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setRequestFingerprint(requestFingerprint);
        attempt.setEmail(email);
        attempt.setUsername(username);
        attempt.setStatus(SelfRegistrationAttemptStatus.SUCCEEDED);
        attempt.setUserId(response.userId());
        attempt.setPersonId(response.personId());
        attempt.setLinkStatus(response.linkStatus());
        attempt.setMatchedExistingPerson(response.matchedExistingPerson());
        attempt.setIssuedTokens(response.issuedTokens());
        applyCrmSummary(attempt, response.crmMatchSummary());
        attempt.setConflictCode(null);
        attempt.setConflictMessage(null);
        attempt.setReferenceId(null);
        selfRegistrationAttemptRepository.save(attempt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordConflict(
            @NonNull String idempotencyKey,
            @NonNull String requestFingerprint,
            @NonNull String email,
            @Nullable String username,
            @NonNull String conflictCode,
            @NonNull String conflictMessage,
            @Nullable UUID referenceId) {
        SelfRegistrationAttempt attempt = selfRegistrationAttemptRepository
                .findByIdempotencyKey(idempotencyKey)
                .orElseGet(SelfRegistrationAttempt::new);
        attempt.setIdempotencyKey(idempotencyKey);
        attempt.setRequestFingerprint(requestFingerprint);
        attempt.setEmail(email);
        attempt.setUsername(username);
        attempt.setStatus(SelfRegistrationAttemptStatus.CONFLICT);
        attempt.setConflictCode(conflictCode);
        attempt.setConflictMessage(conflictMessage);
        attempt.setReferenceId(referenceId);
        selfRegistrationAttemptRepository.save(attempt);
    }

    private void applyCrmSummary(SelfRegistrationAttempt attempt, @Nullable CrmMatchSummaryDto summary) {
        if (summary == null) {
            attempt.setCrmCandidateCount(null);
            attempt.setCrmAnyMatches(null);
            attempt.setCrmIndividualCustomerCandidateCount(null);
            attempt.setCrmCommercialContactCandidateCount(null);
            attempt.setCrmSharedIdentityCandidateCount(null);
            attempt.setCrmExactEmailMatch(null);
            attempt.setCrmExactPhoneMatch(null);
            attempt.setCrmExactNameMatch(null);
            attempt.setCrmReviewRequired(null);
            return;
        }
        attempt.setCrmCandidateCount(summary.getCandidateCount());
        attempt.setCrmAnyMatches(summary.isAnyMatches());
        attempt.setCrmIndividualCustomerCandidateCount(summary.getIndividualCustomerCandidateCount());
        attempt.setCrmCommercialContactCandidateCount(summary.getCommercialContactCandidateCount());
        attempt.setCrmSharedIdentityCandidateCount(summary.getSharedIdentityCandidateCount());
        attempt.setCrmExactEmailMatch(summary.isExactEmailMatch());
        attempt.setCrmExactPhoneMatch(summary.isExactPhoneMatch());
        attempt.setCrmExactNameMatch(summary.isExactNameMatch());
        attempt.setCrmReviewRequired(summary.isReviewRequired());
    }
}
