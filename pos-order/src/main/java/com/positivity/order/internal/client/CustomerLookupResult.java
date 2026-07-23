package com.positivity.order.internal.client;

/**
 * Outcome of a CRM existence check (plan story I2, resolved Q8): {@code UNAVAILABLE} marks the
 * order {@code VALIDATION_PENDING} instead of blocking cart work.
 */
public enum CustomerLookupResult {
    FOUND,
    NOT_FOUND,
    UNAVAILABLE
}
