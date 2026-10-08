package com.positivity.tax.internal.exception;

/**
 * Thrown when an address in a country with a tax profile has no configured rate row for its
 * region on the requested date (CAP:550 S32a, ADR-0071 §3).
 * <p>
 * The request is well formed and the configuration refuses it, so it maps to
 * {@code 422 TAX_JURISDICTION_NOT_CONFIGURED} (ADR-0017 §2). The address is never priced from
 * another country's rates instead.
 */
public class TaxJurisdictionNotConfiguredException extends RuntimeException {

    public TaxJurisdictionNotConfiguredException(String message) {
        super(message);
    }
}
