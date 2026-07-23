package com.positivity.inventory.internal.exception;

import java.time.Instant;

/**
 * Thrown when a replenishment-policy snooze request carries a {@code snoozedUntil} that is
 * not in the future (odoo-parity F3, issue #1041): snoozing until the past or the present
 * instant would be a silent no-op, so it is rejected with a deterministic 422 instead.
 * Clearing a snooze is expressed with a {@code null} {@code snoozedUntil}, never with a
 * past instant.
 */
public class SnoozeUntilNotInFutureException extends RuntimeException {

    public static final String ERROR_CODE = "REPLENISHMENT_SNOOZE_NOT_IN_FUTURE";

    public SnoozeUntilNotInFutureException(Instant snoozedUntil) {
        super("snoozedUntil must be in the future: " + snoozedUntil);
    }
}
