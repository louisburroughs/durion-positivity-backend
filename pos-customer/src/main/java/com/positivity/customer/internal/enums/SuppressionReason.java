package com.positivity.customer.internal.enums;

/**
 * Why an address is hard-blocked from marketing (Story #1140).
 *
 * <p>{@link #HARD_BOUNCE} and {@link #SPAM_COMPLAINT} arrive from the sending provider and
 * protect deliverability reputation; {@link #LEGAL_DNC} is a compliance obligation. All are
 * address-level and outrank any consent the party may later express.
 */
public enum SuppressionReason {
    HARD_BOUNCE,
    SPAM_COMPLAINT,
    LEGAL_DNC,
    MANUAL,
    UNSUBSCRIBE_LINK
}
