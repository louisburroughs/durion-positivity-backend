package com.positivity.supplier.internal.enums;

/**
 * Where a fleet workorder authorization has got to (CAP-323 #1229).
 *
 * <p>Four of these are the vendor's answer. {@link #MANUAL_REVIEW} is not: it is this side stating
 * that it could not get one. The distinction is the whole point of having a fifth value — a shop
 * told "the fleet declined" when in truth the vendor was unreachable will argue with a fleet manager
 * about a decision nobody made.
 */
public enum WorkorderAuthorizationStatus {

    /** Requested, and the vendor has not yet answered. Asynchronous acceptances live here. */
    PENDING,

    /** The fleet program authorized the work. */
    GRANTED,

    /** The fleet program refused. A real answer, and a normal one. */
    DENIED,

    /**
     * The vendor does not know this vehicle, contract or workorder.
     *
     * <p>Distinct from {@link #DENIED} because it is answerable: a fleet number typed wrong is
     * fixed by fixing it, whereas a denial is fixed by talking to the fleet.
     */
    NOT_FOUND,

    /**
     * No decision could be obtained and retrying is not expected to help.
     *
     * <p>Never published as a denial. The row stays here until a human looks at it, because the
     * alternative — retrying forever, or quietly treating silence as refusal — either hides the
     * problem or invents an answer.
     */
    MANUAL_REVIEW;

    /** Whether the vendor has given its final answer, so polling should stop. */
    public boolean isTerminal() {
        return this != PENDING;
    }

    /** Whether work may proceed under a fleet contract. */
    public boolean permitsWork() {
        return this == GRANTED;
    }
}
