package com.positivity.accounting.internal.enums;

/**
 * The kinds of "Needs attention" item of the cash position (CAP:550 S19, #2515; SPEC-accounting-workspace §4.1, §4.5,
 * §4.6): each names something about the cash that a person should look at, never an action the read takes.
 */
public enum CashAttentionCode {
    /** Settlement suspense (2350) holds a balance: processor money not yet matched to a payment. */
    SETTLEMENT_SUSPENSE_NOT_ZERO,
    /** Undeposited register sessions older than the tenant's undeposited-cash threshold; see {@link CashAttentionReason}. */
    UNDEPOSITED_CASH_AGED,
    /** Register cash clearing (1095) holds a balance while no session waits to be deposited. */
    CLEARING_RESIDUAL_NOT_ZERO,
    /** Vendor cash-on-delivery payments unapplied for longer than the tenant's threshold. */
    VENDOR_COD_UNAPPLIED_AGED,
    /** The 30-day outlook's low point is strictly below the tenant's cash safety cushion. */
    BELOW_SAFETY_CUSHION
}
