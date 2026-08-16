package com.positivity.order.internal.enums;

/**
 * Where a purchase order's transmission to its vendor has got to (CAP-320 #1330, ADR-0052).
 *
 * <p>This domain's own state, not a mirror of pos-supplier's intent ledger. The buyer looks at the
 * order; a state that exists only in the transmission log is a state the buyer cannot see.
 */
public enum TransmissionState {
    /**
     * Never sent.
     *
     * <p>Only ever the starting state. A re-request from {@code REJECTED} moves straight to
     * {@code REQUESTED} rather than passing back through here — an order that has been sent once
     * has a transmission history, and returning it to "never sent" would erase that.
     */
    NOT_TRANSMITTED,

    /**
     * Sent, no answer yet. Re-requesting from here is refused: a blind re-send is how one purchase
     * order becomes two deliveries (ADR-0052).
     */
    REQUESTED,

    /** The vendor accepted it. */
    CONFIRMED,

    /** The vendor refused it. Re-ordering is this domain's job, as a fresh request. */
    REJECTED,

    /**
     * pos-supplier could not establish whether the vendor received it, and an operator must decide.
     *
     * <p>Not an outcome — that is precisely why it matters here. An order in this state has no
     * answer and must not be re-sent on a guess, so the buyer needs to see it rather than watch a
     * request sit in REQUESTED forever with nothing to explain it.
     */
    MANUAL_REVIEW
}
