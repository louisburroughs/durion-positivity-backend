package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.VendorBillCommands;
import com.positivity.accounting.internal.dto.VendorBillResponse;
import com.positivity.accounting.internal.dto.VendorBillReview;
import com.positivity.accounting.internal.enums.VendorBillStage;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.data.domain.Page;

/**
 * The vendor-bill approval lifecycle (CAP:550 S12, #2509; SPEC-accounting-workspace §4.3; AW8, AW37-AW43):
 * submit, approve, reject, the resolution of a match exception, the selection of a match candidate, the void of an
 * approved bill, and the reads of the Bills to pay review. The actor is always the caller in the security context
 * (ADR-0018); every transition locks the bill row, so of two concurrent decisions one wins and the other is refused
 * with 409 {@code AP_BILL_NOT_APPROVABLE} naming the status it found.
 *
 * <p>Approval writes the approval fields and posts the bill in one transaction (AW37): a refusal of the posting
 * (closed or hard-locked period, missing mapping, no class) rolls the approval back, and one refusal audit row is
 * kept.
 *
 * <p><b>Before a bill is sent, approved or accepted</b> (409 or 422, nothing written): no ambiguous match naming it
 * may be open ({@code AP_BILL_NOT_APPROVABLE}: pick the match first); a goods-receipt bill needs its vendor invoice
 * matched ({@code AP_BILL_AWAITING_INVOICE}, AW45); a bill of 0.00 has nothing to post ({@code AP_BILL_ZERO_TOTAL});
 * and the vendor's totals must add up or come with a {@code difference} ({@code AP_BILL_TOTALS_UNRECONCILED}, AW47).
 *
 * <p><b>Replays.</b> The commands take no idempotency key: a transition is its own guard. Sent again after it
 * succeeded, a command finds the bill already moved on and is refused with 409 naming the status found ({@code
 * AP_BILL_NOT_APPROVABLE}, {@code AP_BILL_NOT_VOIDABLE}, {@code AP_MATCH_CANDIDATE_ALREADY_RESOLVED}); the client
 * reads the bill to see the outcome. Nothing is posted twice: the posting's durable key backs the status guard.
 */
public interface VendorBillApprovalService {

    /** {@code PENDING_RECEIPT_MATCH | MATCH_EXCEPTION -> AWAITING_APPROVAL}, with a justification. */
    @NonNull
    VendorBillResponse submitForApproval(@NonNull UUID billId, VendorBillCommands.@NonNull Submit command);

    /** {@code AWAITING_APPROVAL -> APPROVED}; posts the bill in the same transaction. */
    @NonNull
    VendorBillResponse approve(@NonNull UUID billId, VendorBillCommands.@NonNull Approve command);

    /** {@code AWAITING_APPROVAL -> REJECTED}, with a reason. Posts nothing. */
    @NonNull
    VendorBillResponse reject(@NonNull UUID billId, VendorBillCommands.@NonNull Reject command);

    /**
     * Resolves a {@code MATCH_EXCEPTION}: {@code ACCEPT} approves and posts, {@code CORRECT} returns the bill to
     * {@code PENDING_RECEIPT_MATCH} without approving, {@code VOID} voids it. Each action is checked against its own
     * permission.
     */
    @NonNull
    VendorBillResponse resolveException(@NonNull UUID billId, VendorBillCommands.@NonNull ResolveException command);

    /** Picks one bill among an ambiguous match's candidates; it moves to {@code AWAITING_APPROVAL}, never approved. */
    @NonNull
    VendorBillResponse selectCandidate(@NonNull UUID candidateId);

    /**
     * Voids a bill: {@code APPROVED -> VOIDED} while nothing is allocated, reversing the entry on the void date
     * (AW42); or {@code PENDING_RECEIPT_MATCH -> VOIDED} for a goods-receipt bill no invoice will match, posting
     * nothing (AW45).
     */
    @NonNull
    VendorBillResponse voidBill(@NonNull UUID billId, VendorBillCommands.@NonNull VoidBill command);

    /** The bill read for the review screen. */
    @NonNull
    VendorBillResponse getBill(@NonNull UUID billId);

    /** How many bills are in each stage. */
    VendorBillReview.@NonNull StageCounts stageCounts();

    /** One page of a stage, in the server's order. */
    @NonNull
    Page<VendorBillReview.StageRow> listByStage(@NonNull VendorBillStage stage, int page, int size);
}
