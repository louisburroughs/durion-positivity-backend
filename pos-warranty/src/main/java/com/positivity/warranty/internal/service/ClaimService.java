package com.positivity.warranty.internal.service;

import com.positivity.warranty.internal.dto.ClaimActionRequest;
import com.positivity.warranty.internal.dto.ClaimCreateRequest;
import com.positivity.warranty.internal.dto.ClaimDecisionRequest;
import com.positivity.warranty.internal.dto.ClaimLineRequest;
import com.positivity.warranty.internal.dto.ClaimNoteRequest;
import com.positivity.warranty.internal.dto.ClaimResponse;
import com.positivity.warranty.internal.dto.ClaimSummaryResponse;
import com.positivity.warranty.internal.dto.ClaimUpdateRequest;
import com.positivity.warranty.internal.enums.ClaimStatus;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Claim core lifecycle (PRD §3.4-3.5, §5, §7, §8): intake, editing while
 * {@code DRAFT}/{@code INFO_NEEDED}, submit (runs eligibility), adjudication, terminal moves,
 * and the audit trail. Every status change appends a {@code ClaimStatusHistory} row; illegal
 * lifecycle moves raise {@code IllegalClaimStateException} carrying the legal next moves.
 */
public interface ClaimService {

    /**
     * Create a DRAFT claim: allocates the claim code, snapshots VIN/odometer from
     * pos-vehicle-inventory, flags {@code originUnverified} when no origin document is given.
     */
    @NonNull
    ClaimResponse create(@NonNull ClaimCreateRequest request);

    /** @throws com.positivity.warranty.internal.exception.WarrantyNotFoundException when {@code id} is unknown */
    @NonNull
    ClaimResponse getById(@NonNull UUID id);

    /** Paged claim search; every filter optional (null = no constraint). */
    @NonNull
    Page<ClaimSummaryResponse> search(
            @Nullable UUID customerId,
            @Nullable UUID vehicleId,
            @Nullable ClaimStatus status,
            @Nullable String claimCode,
            @Nullable UUID locationId,
            @NonNull Pageable pageable);

    /** Edit intake fields; only legal while {@code DRAFT} or {@code INFO_NEEDED}. */
    @NonNull
    ClaimResponse update(@NonNull UUID id, @NonNull ClaimUpdateRequest request);

    /** Add a claim line; only legal while {@code DRAFT} or {@code INFO_NEEDED}. */
    @NonNull
    ClaimResponse addLine(@NonNull UUID id, @NonNull ClaimLineRequest request);

    /** Remove a claim line; only legal while {@code DRAFT} or {@code INFO_NEEDED}. */
    @NonNull
    ClaimResponse removeLine(@NonNull UUID id, @NonNull UUID lineId);

    /** Attach a photo URL; only legal while {@code DRAFT} or {@code INFO_NEEDED}. */
    @NonNull
    ClaimResponse addPhoto(@NonNull UUID id, @NonNull String url);

    /** Detach a photo URL; only legal while {@code DRAFT} or {@code INFO_NEEDED}. */
    @NonNull
    ClaimResponse removePhoto(@NonNull UUID id, @NonNull String url);

    /**
     * Intake complete (PRD §8): runs {@link EligibilityService#evaluate}, enforces photo
     * evidence when the winning policy requires it, then moves {@code DRAFT → SUBMITTED} or
     * {@code INFO_NEEDED → IN_REVIEW} (info provided).
     */
    @NonNull
    ClaimResponse submit(@NonNull UUID id);

    /** (Re)compute the eligibility suggestion on demand (PRD §8); no status change. */
    @NonNull
    ClaimResponse evaluateEligibility(@NonNull UUID id);

    /**
     * Adjudicate (PRD §5 rules): implicit {@code SUBMITTED → IN_REVIEW} on first decision
     * touch; APPROVE records the decision actor; DENY and APPEAL require a reason; a decision
     * contradicting the computed suggestion requires a reason and sets
     * {@code overrodeSuggestion}; APPEAL reopens a DENIED claim ({@code DENIED → IN_REVIEW}).
     */
    @NonNull
    ClaimResponse decide(@NonNull UUID id, @NonNull ClaimDecisionRequest request);

    /** Cancel from DRAFT/SUBMITTED/INFO_NEEDED (state machine enforced). */
    @NonNull
    ClaimResponse cancel(@NonNull UUID id, @NonNull ClaimActionRequest request);

    /**
     * Close from SETTLED/DENIED; blocked (409 with {@code nextAction}) until every vendor
     * reimbursement and every part return of the claim is terminal (PRD §5).
     */
    @NonNull
    ClaimResponse close(@NonNull UUID id, @NonNull ClaimActionRequest request);

    /** Append a staff note (allowed in any status — notes are audit trail). */
    @NonNull
    ClaimResponse addNote(@NonNull UUID id, @NonNull ClaimNoteRequest request);
}
