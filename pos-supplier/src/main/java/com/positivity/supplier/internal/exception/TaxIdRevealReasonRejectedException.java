package com.positivity.supplier.internal.exception;

/**
 * A reveal whose reason contains the very number it would reveal (#2621; Security confirmation on
 * louisburroughs/durion#571). Answered 400 {@code VALIDATION_ERROR}; nothing is revealed, and the reveal
 * keeps its {@code REASON_REJECTED} audit row: the service returns that outcome and the transaction commits; only then
 * does the controller throw this (ADR-0072 Decision 4). The message names the rule only: it
 * never carries the reason or the number.
 */
public class TaxIdRevealReasonRejectedException extends SupplierValidationException {

    public TaxIdRevealReasonRejectedException() {
        super(
                VALIDATION_ERROR,
                "reason must not contain the registration number",
                java.util.List.of(new com.positivity.shared.error.ApiError.FieldError(
                        "reason", "must not contain the registration number")));
    }
}
