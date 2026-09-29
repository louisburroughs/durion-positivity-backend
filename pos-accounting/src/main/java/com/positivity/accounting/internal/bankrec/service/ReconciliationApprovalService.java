package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.dto.BankReconciliationResponse;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationJustificationRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationReasonRequest;
import com.positivity.accounting.internal.bankrec.dto.ReconciliationTransitionRequest;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The approval workflow of a bank reconciliation (SPEC-manual-bank-reconciliation §3.8, §4.9; story S5,
 * #2304): submit and approve behind the gate E4, return to the preparer, cancel, and supersede an approved or
 * invalidated reconciliation. Every transition writes one audit row and, except return, one outbox fact.
 */
public interface ReconciliationApprovalService {

    /** {@code IN_PROGRESS → SUBMITTED} behind E4 (preparer). */
    @NonNull
    BankReconciliationResponse submit(
            @NonNull UUID reconciliationId, @Nullable ReconciliationTransitionRequest request);

    /**
     * {@code SUBMITTED → FINALIZED} (approver ≠ submitter unless the tenant allows self-approval): E4 on the live
     * balance under the row lock, the approval snapshots, and a predecessor marked {@code SUPERSEDED}.
     */
    @NonNull
    BankReconciliationResponse approve(
            @NonNull UUID reconciliationId, @Nullable ReconciliationTransitionRequest request);

    /** {@code SUBMITTED → IN_PROGRESS} with a reason (approver). */
    @NonNull
    BankReconciliationResponse returnToPreparer(
            @NonNull UUID reconciliationId, @NonNull ReconciliationReasonRequest request);

    /** {@code IN_PROGRESS | SUBMITTED → CANCELLED}: matches unmatched, registered items released (approver). */
    @NonNull
    BankReconciliationResponse cancel(
            @NonNull UUID reconciliationId, @NonNull ReconciliationJustificationRequest request);

    /**
     * A new {@code IN_PROGRESS} reconciliation of the same statement superseding a {@code FINALIZED} or {@code
     * INVALIDATED} one: the predecessor's members are released and re-proposed in the successor (approver).
     */
    @NonNull
    BankReconciliationResponse supersede(
            @NonNull UUID reconciliationId, @NonNull ReconciliationJustificationRequest request);
}
