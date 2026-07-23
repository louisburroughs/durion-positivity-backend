package com.positivity.inventory.internal.exception;

import java.time.Instant;

/**
 * Thrown when a point-in-time (as-of) inventory query requests a future instant (odoo-parity
 * A3, issue #1029). Historical ledger aggregation is only defined for the past; future
 * projections are the forecast quantities' job (A2). Maps to a deterministic 422.
 */
public class AsOfInFutureException extends RuntimeException {

    public AsOfInFutureException(Instant asOf) {
        super("asOf must not be in the future: " + asOf);
    }
}
