package com.positivity.tax.internal.exception;

/**
 * Thrown when jurisdiction rate lookup ({@code GET /v1/tax/rates}) is requested while the
 * active tax provider is not the test-mode calculator (issue #1522).
 * <p>
 * Neither the Avalara adapter nor the legacy external stub implements a rate-only call today
 * — Avalara only exposes the full transaction API in this codebase, and
 * {@code /api/v2/taxrates/byaddress} is a documented follow-up, not built. Rather than
 * synthesizing an estimate from unrelated data, the endpoint fails loudly with this exception,
 * mapped to {@code 501 Not Implemented}.
 */
public class TaxRateLookupUnsupportedException extends RuntimeException {

    public TaxRateLookupUnsupportedException(String message) {
        super(message);
    }
}
