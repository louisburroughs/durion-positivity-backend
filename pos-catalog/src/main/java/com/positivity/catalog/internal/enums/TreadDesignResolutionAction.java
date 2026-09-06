package com.positivity.catalog.internal.enums;

/**
 * What a reviewer decided about a tread design awaiting review (#1645).
 *
 * <p>Three actions rather than a free-form state change: a reviewer's vocabulary is "yes, these
 * products", "no, none of these" and "not now", and letting the API accept any target state would
 * make it possible to write states — MATCHED with nothing attached, say — that no rule produces.
 */
public enum TreadDesignResolutionAction {

    /** Attach the named products by hand. Requires at least one product; state becomes MATCHED. */
    ATTACH,

    /**
     * None of the candidates is right; state becomes REJECTED. Attachments a person previously made
     * are left alone — rejecting the machine's suggestions says nothing about a human decision.
     */
    REJECT,

    /** Decide later, optionally after a date; state becomes DEFERRED. */
    DEFER
}
