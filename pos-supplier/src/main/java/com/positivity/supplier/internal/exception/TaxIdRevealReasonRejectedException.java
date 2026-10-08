package com.positivity.supplier.internal.exception;

/**
 * A reveal whose reason contains the very number it would reveal (#2621; Security confirmation on
 * louisburroughs/durion#571). Answered 400 {@code VALIDATION_ERROR}; nothing is revealed, and the reveal
 * keeps its {@code REASON_REJECTED} audit row ({@code noRollbackFor}). The message names the rule only: it
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
