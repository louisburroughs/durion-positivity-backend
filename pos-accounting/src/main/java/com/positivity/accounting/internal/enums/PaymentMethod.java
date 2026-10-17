package com.positivity.accounting.internal.enums;

/**
 * Payment Method enum for AP payments.
 */
public enum PaymentMethod {
    ACH,
    CHECK,
    WIRE,
    CREDIT_CARD,
    /**
     * Cash paid out of a register drawer to a vendor on delivery (CAP:550 S17, #2513; AW41). Created by the
     * session-close posting, never by the pay command; posts nothing of its own (the close entry, Dr 2000 / Cr 1095,
     * is its posting); allocated to the vendor's approved bill later.
     */
    CASH,
    OTHER
}
