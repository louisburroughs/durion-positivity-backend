package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.AutoMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationCandidatesResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchCreateRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchDecisionRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationMatchResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationUnmatchRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Matching in a reconciliation (SPEC-manual-bank-reconciliation §3.4, §4.6; story S4, #2303): human matches
 * (1:1, 1:N, N:1) under M1–M5, accept / reject of proposals, unmatch with a reason (M7: never deleted),
 * deterministic candidates and a propose-only auto-match (M6, D12).
 */
public interface ReconciliationMatchingService {

    /** Create an ACCEPTED human match; a replayed requestId returns it. */
    @NonNull
    ReconciliationMatchResponse createMatch(
            @NonNull UUID reconciliationId, @NonNull ReconciliationMatchCreateRequest request);

    /** Accept a PROPOSED match. */
    @NonNull
    ReconciliationMatchResponse accept(
            @NonNull UUID reconciliationId, @NonNull UUID matchId, @NonNull ReconciliationMatchDecisionRequest request);

    /** Reject a PROPOSED match. */
    @NonNull
    ReconciliationMatchResponse reject(
            @NonNull UUID reconciliationId, @NonNull UUID matchId, @NonNull ReconciliationMatchDecisionRequest request);

    /** Unmatch an ACCEPTED match with a reason. */
    @NonNull
    ReconciliationMatchResponse unmatch(
            @NonNull UUID reconciliationId, @NonNull UUID matchId, @NonNull ReconciliationUnmatchRequest request);

    /** Ranked candidates for one bank transaction or one ledger line. */
    @NonNull
    ReconciliationCandidatesResponse candidates(
            @NonNull UUID reconciliationId,
            @Nullable UUID bankTransactionId,
            @Nullable UUID glLineId,
            @Nullable Integer windowDays);

    /** Propose one-to-one RULE matches for unexplained bank transactions with a clear top candidate. */
    @NonNull
    AutoMatchResponse autoMatch(@NonNull UUID reconciliationId);
}
