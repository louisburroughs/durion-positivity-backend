package com.positivity.invoice.internal.enums;

/**
 * Lifecycle status of a payment intent.
 *
 * <p>
 * Transitions:
 * <ul>
 * <li>{@code PENDING} — payment submitted to gateway, awaiting response</li>
 * <li>{@code AUTHORIZED} — authorization hold placed (AUTH_ONLY flow)</li>
 * <li>{@code CAPTURED} — funds captured successfully</li>
 * <li>{@code CAPTURE_FAILED} — capture attempt failed; no funds moved</li>
 * <li>{@code EXPIRED} — authorization hold expired before capture</li>
 * </ul>
 */
public enum PaymentIntentStatus {
    PENDING,
    AUTHORIZED,
    CAPTURED,
    CAPTURE_FAILED,
    EXPIRED
}
