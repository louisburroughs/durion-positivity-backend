package com.positivity.tax.internal.exception;

/**
 * Thrown when a calculation for a profiled country states a transaction currency other than the
 * country's configured currency (CAP:550 S32a; ADR-0067 PC-9).
 * <p>
 * The plug-in rounds every row at its profile currency's exponent and never converts, so a
 * mismatched request is refused with {@code 422 CURRENCY_NOT_SUPPORTED} rather than priced under
 * the wrong currency's rules. There is no implicit currency (ADR-0067 R-2).
 */
public class TaxCurrencyNotSupportedException extends RuntimeException {

    public TaxCurrencyNotSupportedException(String message) {
        super(message);
    }
}
