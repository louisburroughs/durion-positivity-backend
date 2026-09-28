package com.positivity.accounting.internal.enums;

/**
 * Vendor Bill (AP) lifecycle states.
 *
 * <p>
 * Receipt Accrual Workflow (Issue #130):
 * <ol>
 * <li>PENDING_RECEIPT_MATCH: Created from GoodsReceivedEvent, awaiting
 * invoice</li>
 * <li>APPROVED: Three-way match successful, ready for payment</li>
 * <li>MATCH_EXCEPTION: Discrepancy detected, awaits manual resolution</li>
 * <li>CURRENCY_HOLD: Stated in a currency other than the ledger's; held (#2309)</li>
 * <li>PAID: Payment processed</li>
 * <li>VOIDED: Cancelled/reversed</li>
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
     * Bill created from GoodsReceivedEvent, awaiting VendorInvoiceReceivedEvent for
     * three-way
     * match. GL posting: Dr Inventory/Expense, Cr AP (provisional).
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
     * Bill has been approved for payment.
     */
    APPROVED,

    /**
     * Bill has been rejected (incorrect amount, missing documentation, etc.).
     */
    REJECTED,

    /**
     * Bill has been paid and payment recorded.
     */
    PAID,

    /**
     * Bill voided/reversed (replaces CANCELLED in new workflows).
     */
    VOIDED
}
