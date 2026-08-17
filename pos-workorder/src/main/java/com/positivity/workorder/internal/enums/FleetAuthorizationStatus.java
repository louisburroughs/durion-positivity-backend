package com.positivity.workorder.internal.enums;

/**
 * Where a workorder's fleet payment authorization stands (#1346).
 *
 * <h2>Not a workorder status</h2>
 *
 * This is a payer-authorization lifecycle, deliberately separate from {@code WorkorderStatus}. The
 * domain ruled that fleet authorization records whether the Commercial Customer agrees to pay, while
 * {@code AWAITING_APPROVAL} means customer consent for added scope during execution. Folding them
 * together would make "who is paying" and "how far along is the work" the same question, and would
 * silently change the meaning of an existing state.
 *
 * <h2>Three of these are the vendor's answer; two are not</h2>
 *
 * {@link #GRANTED}, {@link #REFUSED} and {@link #PENDING} come from the fleet program and are
 * preserved without reinterpretation. {@link #MANUAL_REVIEW} is <em>this platform</em> reporting that
 * nobody could get an answer, and {@link #CANCELLED} is an advisor deciding to stop asking. Neither
 * is a refusal, and the difference is the whole point: telling a shop the fleet said no when nobody
 * could reach the fleet sends it to argue about a decision that was never made.
 */
public enum FleetAuthorizationStatus {

    /** Required, and not yet asked for. */
    NOT_REQUESTED,

    /**
     * Asked, and no answer yet — including an asynchronous acceptance the vendor will resolve later.
     *
     * <p>Keeps the start gate closed. "We have not heard back" is not permission.
     */
    PENDING,

    /** The fleet program agreed to pay. The only status that opens the start gate. */
    GRANTED,

    /**
     * The fleet program declined to pay. A real answer, and a normal one.
     *
     * <p>Leaves the workorder open. Nothing is cancelled or converted automatically — an advisor
     * decides explicitly what happens next, per the domain ruling.
     */
    REFUSED,

    /**
     * No answer could be obtained, and retrying is not expected to help.
     *
     * <p>A <em>pending disposition</em>, never a denial: it keeps the gate closed exactly as
     * {@link #PENDING} does. A timeout or a communication failure must not manufacture a refusal.
     */
    MANUAL_REVIEW,

    /** An advisor stopped pursuing this authorization. The workorder needs another payer or none. */
    CANCELLED;

    /**
     * Whether work may start under this authorization.
     *
     * <p>Only a grant opens the gate. Every other value — including the two that are not the
     * vendor's fault — keeps it shut, because none of them is permission to spend a fleet's money.
     */
    public boolean permitsStart() {
        return this == GRANTED;
    }

    /** Whether the fleet has given its final answer, so polling and re-asking should stop. */
    public boolean isVendorDecided() {
        return this == GRANTED || this == REFUSED;
    }

    /** Whether an advisor still has something to resolve. */
    public boolean needsAttention() {
        return this == REFUSED || this == MANUAL_REVIEW;
    }
}
