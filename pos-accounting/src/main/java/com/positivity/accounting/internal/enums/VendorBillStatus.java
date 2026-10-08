package com.positivity.accounting.internal.enums;

/**
 * Vendor Bill (AP) lifecycle states.
 *
 * <p>
 * Receipt Accrual Workflow (Issue #130):
 * <ol>
 * <li>PENDING_RECEIPT_MATCH: Created from GoodsReceivedEvent, awaiting
 * invoice</li>
 * <li>MATCH_EXCEPTION: Discrepancy detected, awaits manual resolution</li>
 * <li>CURRENCY_HOLD: Stated in a currency other than the ledger's; held (#2309)</li>
 * <li>AWAITING_APPROVAL: Sent for approval; approved or rejected by a person allowed to (#2509, AW8)</li>
 * <li>APPROVED: Approved and posted in one transaction (AW37); ready for payment</li>
 * <li>REJECTED: Rejected while awaiting approval; terminal</li>
 * <li>PAID: Payment processed</li>
 * <li>VOIDED: Voided from MATCH_EXCEPTION, or from APPROVED while nothing is allocated (AW42)</li>
 * </ol>
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract
 *      Guide</a>
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/130">Issue
 *      #130</a>
 */
public enum VendorBillStatus {
    /**
     * Bill created from a GoodsReceivedEvent or a supplier invoice, awaiting its three-way match or a person's
     * decision to send it for approval without one. Nothing is posted (AW37).
     */
    PENDING_RECEIPT_MATCH,

    /**
     * Three-way match exception: quantity or price variance exceeds tolerance.
     * Requires manual
     * resolution (accept, correct, or void).
     */
    MATCH_EXCEPTION,

    /**
     * Bill stated in a currency other than the ledger currency (ADR-0067 PC-9, PC-13; issue #2309).
     * Held, never booked at par: not matched, not approvable through match resolution, not paid,
     * and outside every ledger-currency total (Aged Payables). The reason is in
     * {@code rejectionReason}. A later stage releases it (a booking rate from Stage B1, or manual
     * handling).
     */
    CURRENCY_HOLD,

    /**
     * Sent for approval (#2509, AW8): by a person with a justification, by a HIGH match ({@code submittedBy =
     * SYSTEM}) or by a candidate selection. Approve moves it to {@link #APPROVED}, reject to {@link #REJECTED}.
     * Nothing is posted yet.
     */
    AWAITING_APPROVAL,

    /**
     * Approved, and posted in the same transaction (AW37): approved if and only if posted. Locked; paid in parts,
     * it stays APPROVED. Voided only while nothing is allocated to it (AW42).
     */
    APPROVED,

    /**
     * Rejected while awaiting approval (incorrect amount, missing documentation, etc.); terminal. Never posted.
     */
    REJECTED,

    /**
     * Bill has been paid and payment recorded.
     */
    PAID,

    /**
     * Voided: from MATCH_EXCEPTION (never posted), or from APPROVED while nothing is allocated, its entry reversed on
     * the void date (AW42). Terminal.
     */
    VOIDED
}
