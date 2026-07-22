package com.positivity.inventory.internal.exception;

/**
 * Thrown by the ledger posting funnel when a posting violates the
 * per-event-type negative-stock policy matrix (odoo-parity K1, issue #1027).
 *
 * <p>Carries a deterministic per-case error code surfaced in the
 * {@code ApiError} envelope:
 * <ul>
 *   <li>{@link #OVERRIDE_REQUIRED} — a {@code BLOCKED_OVERRIDABLE} event type
 *       (SCRAP_OUT) would take on-hand below zero and no override flag was
 *       passed;</li>
 *   <li>{@link #FLOOR_VIOLATION} — a {@code FLOOR_AT_ZERO} event type
 *       (ADJUSTMENT_OUT, COUNT_VARIANCE_OUT, ADJUST_CYCLE_COUNT) would take
 *       on-hand below zero; never overridable.</li>
 * </ul>
 *
 * <p>{@code BLOCKED} event types keep surfacing the pre-existing
 * {@link InsufficientStockException} ({@code INSUFFICIENT_STOCK}).
 */
public class NegativeStockPolicyViolationException extends RuntimeException {

    /** SCRAP_OUT below zero without the caller-approved override flag. */
    public static final String OVERRIDE_REQUIRED = "NEGATIVE_STOCK_OVERRIDE_REQUIRED";

    /** Floor-at-zero event type would drive on-hand negative. */
    public static final String FLOOR_VIOLATION = "NEGATIVE_STOCK_FLOOR_VIOLATION";

    private final String errorCode;

    public NegativeStockPolicyViolationException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
