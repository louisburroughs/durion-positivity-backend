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
 * <li>PAID: Payment processed</li>
 * <li>VOIDED: Cancelled/reversed</li>
 * </ol>
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide</a>
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/130">Issue
 *      #130</a>
 */
public enum VendorBillStatus {
    /**
     * Bill created from GoodsReceivedEvent, awaiting VendorInvoiceReceivedEvent for
     * three-way match.
     * GL posting: Dr Inventory/Expense, Cr AP (provisional).
     */
    PENDING_RECEIPT_MATCH,

    /**
     * Three-way match exception: quantity or price variance exceeds tolerance.
     * Requires manual resolution (accept, correct, or void).
     */
    MATCH_EXCEPTION,

    /**
     * Bill is awaiting approval review by AP clerk or accountant (legacy state).
     * 
     * @deprecated Use PENDING_RECEIPT_MATCH for new workflows.
     */
    @Deprecated
    PENDING_REVIEW,

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
     * Bill has been cancelled (voided before payment).
     * 
     * @deprecated Use VOIDED for new workflows.
     */
    @Deprecated
    CANCELLED,

    /**
     * Bill voided/reversed (replaces CANCELLED in new workflows).
     */
    VOIDED
}
