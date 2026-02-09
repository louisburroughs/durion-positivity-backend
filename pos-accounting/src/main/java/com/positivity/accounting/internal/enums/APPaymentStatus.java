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
    GL_POST_FAILED
}
