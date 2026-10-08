package com.positivity.accounting.internal.enums;

/**
 * Where a vendor's unreconciled difference between the gross and net + tax posts (AW47): the person sending, approving
 * or accepting the bill decides, with a justification. A negative difference is a credit.
 */
public enum VendorBillDifferenceClass {
    /** Freight the vendor did not state separately: {@code FREIGHT_IN} (5060). */
    FREIGHT,
    /** More goods: {@code GOODS_RECEIVED_NOT_BILLED} (2100). */
    GOODS,
    /** An expense: the {@code VENDOR_BILL} key {@code EXPENSE_<CODE>} given with it. */
    EXPENSE,
    /** A price difference: {@code PURCHASE_PRICE_DIFFERENCE} (5050). */
    PRICE_DIFFERENCE
}
