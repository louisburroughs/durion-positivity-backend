package com.positivity.tax.internal.exception;

/**
 * Thrown when a calculation type is requested from a provider that does not support it (CAP:550 S43): today {@code
 * USE} (self-assessed tax) on the external providers ({@code EXTERNAL}, {@code AVALARA}). Test mode and every
 * self-hosted plug-in price it like {@code SALE}. Mapped to {@code 501 TAX_CALCULATION_TYPE_UNSUPPORTED}, the {@code
 * TAX_RATE_LOOKUP_UNSUPPORTED} precedent for a documented stub path; it moves to 422 {@code
 * TAX_CAPABILITY_UNSUPPORTED} when the capability binding (#2629) lands.
 */
public class TaxCalculationTypeUnsupportedException extends RuntimeException {

    public TaxCalculationTypeUnsupportedException(String message) {
        super(message);
    }
}
