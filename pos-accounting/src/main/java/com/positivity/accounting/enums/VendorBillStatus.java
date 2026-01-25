package com.positivity.accounting.enums;

/**
 * Vendor Bill (AP) lifecycle states.
 * 
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide</a>
 */
public enum VendorBillStatus {
    /**
     * Bill is awaiting approval review by AP clerk or accountant.
     */
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
     */
    CANCELLED
}
