package com.positivity.supplier.internal.enums;

/**
 * Persisted attempt state of one transmission intent (ADR-0052 §2).
 *
 * <p>The transitions into {@link #DISPATCHING} and out of it are committed <em>before</em> the
 * network I/O they describe. That ordering is the whole mechanism: it is what makes a crash
 * after body-send distinguishable from a never-sent order, and therefore what keeps a restart
 * from placing a second physical order.
 *
 * <h2>Reading the states as "may this be dispatched?"</h2>
 *
 * <ul>
 *   <li>{@link #PENDING} — yes. Nothing has been sent; the vendor cannot know about it.
 *   <li>{@link #DISPATCHING} — <strong>never automatically.</strong> The body may have reached
 *       the vendor. Recovery reconciles through order status, or goes straight to
 *       {@link #MANUAL_REVIEW} when the profile has no {@code ORDER_STATUS} binding (§4).
 *   <li>{@link #SENT_AWAITING_RESULT} — no. The vendor holds it and we are waiting.
 *   <li>{@link #MANUAL_REVIEW} — no, and no frontend surface offers a blind re-send (§3).
 *   <li>terminal states — no. Ordering again is a <em>new</em> intent with a new document id.
 * </ul>
 */
public enum TransmissionAttemptState {

    /** Intent recorded, no send attempted. Safe to dispatch. */
    PENDING(false, false),

    /**
     * Persisted immediately before the request body is sent. Automatic resend is forbidden from
     * here — this is the state that exists solely so a crash cannot be misread as "never sent".
     */
    DISPATCHING(false, false),

    /** The vendor received the document and we are awaiting its decision. */
    SENT_AWAITING_RESULT(false, false),

    /** The vendor accepted the order. Terminal for the transmission; fulfilment continues. */
    CONFIRMED(true, false),

    /** The vendor refused the order on its merits. Terminal. */
    REJECTED(true, true),

    /**
     * A post-send ambiguous outcome awaits a human (ADR-0052 §3/§4). Not terminal: an operator
     * resolves it into {@link #CONFIRMED}, {@link #FAILED} or {@link #CANCELLED}.
     */
    MANUAL_REVIEW(false, false),

    /**
     * An operator established the vendor never received the order
     * ({@code mark-not-received}, §4). Terminal, and never re-dispatched: re-ordering requires a
     * new intent and therefore a new document id.
     */
    FAILED(true, true),

    /** An operator abandoned the transmission ({@code cancel}, §4). Terminal. */
    CANCELLED(true, true);

    private final boolean terminal;
    private final boolean reorderable;

    TransmissionAttemptState(boolean terminal, boolean reorderable) {
        this.terminal = terminal;
        this.reorderable = reorderable;
    }

    /** Whether this transmission will not change state again on its own. */
    public boolean isTerminal() {
        return terminal;
    }

    /**
     * Whether the purchase order may be transmitted again under a fresh intent for the same
     * revision.
     *
     * <p>False for {@link #CONFIRMED} on purpose, and that is the duplicate-order guard: a vendor
     * that accepted an order keeps its claim on that (profile, intent type, purchase order,
     * revision) tuple forever, so a re-issued command for the same tuple is recognised as the
     * repeat it is rather than becoming a second delivery.
     */
    public boolean isReorderable() {
        return reorderable;
    }
}
