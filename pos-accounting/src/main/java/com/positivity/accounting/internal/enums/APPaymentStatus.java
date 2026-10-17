package com.positivity.accounting.internal.enums;

/**
 * AP Payment Status enum.
 *
 * Lifecycle for Bill Payment with Gateway and GL Posting:
 * INITIATED → GATEWAY_PENDING → (GATEWAY_FAILED | GATEWAY_SUCCEEDED)
 * → GL_POST_PENDING → (GL_POSTED | GL_POST_FAILED)
 *
 * <p>
 * States:
 * <ul>
 * <li>INITIATED - Payment request created, not yet submitted to gateway</li>
 * <li>GATEWAY_PENDING - Payment submitted to gateway, awaiting
 * confirmation</li>
 * <li>GATEWAY_FAILED - Gateway rejected/failed payment</li>
 * <li>GATEWAY_SUCCEEDED - Gateway confirmed payment success, allocations
 * applied</li>
 * <li>GL_POST_PENDING - Payment succeeded, waiting for GL journal entry
 * posting</li>
 * <li>GL_POSTED - GL journal entry posted successfully</li>
 * <li>GL_POST_FAILED - GL posting failed after retries, requires manual
 * remediation</li>
 * </ul>
 *
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/128">Issue
 *      #128</a>
 */
public enum APPaymentStatus {
    INITIATED,
    GATEWAY_PENDING,
    GATEWAY_FAILED,
    GATEWAY_SUCCEEDED,
    GL_POST_PENDING,
    GL_POSTED,
    GL_POST_FAILED,
    /**
     * A GATEWAY_PENDING orphan the operator reconciled as never charged (#2729): nothing moved, nothing posted,
     * nothing to reopen. Terminal; its bank account may be deactivated.
     */
    VOIDED,
    /**
     * The charge moved money and came back (#2642, AW42): the allocations are reopened and the mirror entry (the
     * reversal of the payment's entry) is still owed, because the original posting is pending or failed, or the
     * return's date fell in a closed period. {@code glPostError} names the period remedy when there is one.
     */
    RETURN_PENDING,
    /** The returned charge is fully recorded: mirror posted, allocations reopened. Terminal. */
    RETURNED
}
