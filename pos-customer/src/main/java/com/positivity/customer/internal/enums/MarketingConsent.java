package com.positivity.customer.internal.enums;

/**
 * Per-channel marketing consent state (Story #1138).
 *
 * <p>{@link #UNSET} is distinct from {@link #OPT_OUT}: it records that the party has
 * never been asked, which matters for compliance reporting and for a future double
 * opt-in flow. Both are non-sendable — {@link #OPT_IN} is the only sendable state.
 */
public enum MarketingConsent {
    OPT_IN,
    OPT_OUT,
    UNSET;

    /** Only an explicit opt-in permits a marketing send. */
    public boolean allowsMarketing() {
        return this == OPT_IN;
    }
}
