package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.ReimbursementResponse;
import com.positivity.warranty.internal.dto.ReimbursementSubmitRequest;
import com.positivity.warranty.internal.dto.ReimbursementUpdateRequest;
import com.positivity.warranty.internal.enums.ReimbursementStatus;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Vendor-reimbursement child lifecycle (PRD §3.7): the back-office money trail that runs after
 * the customer is settled and never blocks them. Illegal lifecycle moves surface as
 * {@code IllegalClaimStateException} → 409 {@code ApiError} with {@code nextAction}.
 */
public interface ReimbursementService {

    /**
     * Submit the claim's single vendor reimbursement to the provider
     * ({@code NOT_SUBMITTED → SUBMITTED}), creating the record if absent. The claim must be
     * {@code APPROVED} or {@code SETTLED}; {@code DEALER}-funded claims are rejected
     * (reimbursement is {@code NOT_APPLICABLE} for self-funded programs).
     */
    @NonNull
    ReimbursementResponse submit(@NonNull UUID claimId, @NonNull ReimbursementSubmitRequest request);

    /**
     * Record a vendor decision / credit / write-off on the claim's reimbursement. Legal moves:
     * {@code SUBMITTED → APPROVED | PARTIALLY_APPROVED | DENIED},
     * {@code APPROVED | PARTIALLY_APPROVED → CREDIT_RECEIVED}, and any non-terminal state
     * {@code → WRITTEN_OFF}.
     */
    @NonNull
    ReimbursementResponse update(@NonNull UUID claimId, @NonNull ReimbursementUpdateRequest request);

    /** Back-office worklist (PRD §8); both filters optional. */
    @NonNull
    List<ReimbursementResponse> worklist(@Nullable ReimbursementStatus status, @Nullable UUID providerId);
}
