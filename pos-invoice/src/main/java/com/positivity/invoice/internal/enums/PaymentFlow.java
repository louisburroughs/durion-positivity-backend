package com.positivity.invoice.internal.enums;

/**
 * Payment flow selection for a card transaction.
 *
 * <ul>
 * <li>{@code SALE_CAPTURE} — one-step authorization and capture (default)</li>
 * <li>{@code AUTH_ONLY} — two-step: authorize, then capture explicitly
 * later</li>
 * </ul>
 */
public enum PaymentFlow {
    SALE_CAPTURE,
    AUTH_ONLY
}
