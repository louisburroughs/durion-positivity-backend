package com.positivity.accounting.internal.enums;

/**
 * What let an approved vendor bill charging tax on goods for resale through its tax country's {@code HOLD} rule
 * (CAP:550 S43, AW44): stored on {@code vendor_bill.tax_on_resale_override}.
 */
public enum TaxOnResaleOverrideSource {
    /** The approver overrode the hold for this one bill, with a justification of 10-1000 characters. */
    BILL,
    /** The vendor's AP setting {@code acceptTaxOnResaleGoods} accepts such tax on every bill. */
    VENDOR_SETTING
}
