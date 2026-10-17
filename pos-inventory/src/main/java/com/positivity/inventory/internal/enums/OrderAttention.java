package com.positivity.inventory.internal.enums;

/**
 * CAP:550 S36 (#2525, PROPOSED 9): why a CONVERTED purchase suggestion needs a buyer's attention, computed on read
 * from pos-inventory's own copies (never stored). These are the conditions under which pos-order refuses the order's
 * approval or transmission (S24), or never placed it at all.
 */
public enum OrderAttention {
    /** The open order's vendor is INACTIVE in the vendor copy. */
    ORDER_VENDOR_INACTIVE,
    /** The open order's vendor is absent from the vendor copy (e.g. an order that still names a feed id). */
    ORDER_VENDOR_NOT_IN_COPY,
    /** No order row has appeared in the purchase-order copy within the placement grace after conversion. */
    ORDER_NOT_PLACED
}
