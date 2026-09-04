package com.positivity.invoice.internal.exception;

/**
 * An adjustment is shape-valid on its own but, combined with the invoice's existing adjustments,
 * would drive the invoice total negative (issue #1694) — a documented domain-policy violation on
 * an otherwise semantically valid payload (negative totals require a credit memo, not a further
 * DRAFT-invoice adjustment). Maps to HTTP 422 per ADR-0017 §1/§2.
 */
public class ExcessiveAdjustmentException extends RuntimeException {

    public ExcessiveAdjustmentException(String message) {
        super(message);
    }
}
