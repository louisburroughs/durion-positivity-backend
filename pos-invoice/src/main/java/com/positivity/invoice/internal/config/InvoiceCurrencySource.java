package com.positivity.invoice.internal.config;

import java.util.Currency;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The one place pos-invoice learns the ISO 4217 currency its amounts are in.
 *
 * <p>An invoice carries no currency of its own yet, and the tenant's functional currency has no
 * accessor yet (ADR-0067 PC-2, PC-3; step A2). Until one of those lands, every pos-invoice
 * contract that must state a currency — the rendered invoice document, payment events and tax
 * requests — reads it here, so the change is made in one place.
 */
@Component
public class InvoiceCurrencySource {

    private final String currencyCode;

    /**
     * Read from {@code pos.invoice.functional-currency} ({@code POS_INVOICE_FUNCTIONAL_CURRENCY}), the tenant's
     * functional currency (ADR-0067 R-2 / PC-2; #2644), the interim per-module source pos-order also uses until the
     * tenant carries it (#2583). Defaults to USD; refuses a value that is not an ISO 4217 code.
     */
    public InvoiceCurrencySource(@Value("${pos.invoice.functional-currency:USD}") @NonNull String currencyCode) {
        String normalized = currencyCode.trim().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException notIso) {
            throw new IllegalStateException(
                    "pos.invoice.functional-currency (POS_INVOICE_FUNCTIONAL_CURRENCY) must be an ISO 4217 code, was '"
                            + currencyCode + "'",
                    notIso);
        }
        this.currencyCode = normalized;
    }

    /** The ISO 4217 code of the amounts pos-invoice records and publishes: the tenant's functional currency. */
    @NonNull
    public String currencyCode() {
        return currencyCode;
    }
}
