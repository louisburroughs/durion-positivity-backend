package com.positivity.supplier.internal.enums;

/**
 * Retry backoff strategy of a vendor profile's protocol defaults (ADR-0050 §2, architecture
 * doc §7 {@code protocolDefaults.retry.backoff}). {@code null} on the profile means the
 * deployment default.
 */
public enum RetryBackoff {
    /** Constant delay between retry attempts. */
    FIXED,
    /** Exponentially growing delay between retry attempts. */
    EXPONENTIAL
}
