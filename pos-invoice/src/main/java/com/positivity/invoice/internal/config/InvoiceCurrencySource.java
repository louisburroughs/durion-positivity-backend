package com.positivity.invoice.internal.config;

import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

/**
 * The one place pos-invoice learns the ISO 4217 currency its amounts are in.
 *
 * <p>An invoice carries no currency of its own yet, and the tenant's functional currency has no
 * accessor yet (ADR-0067 PC-2, PC-3; step A2). Until one of those lands, every pos-invoice
 * contract that must state a currency — the rendered invoice document, payment events and tax
 * requests — reads it here, so the change is made in one place. Every tenant bills in USD today.
 */
@Component
public class InvoiceCurrencySource {

    private static final String CURRENCY_CODE = "USD";

    /** The ISO 4217 code of the amounts pos-invoice records and publishes. */
    @NonNull
    public String currencyCode() {
        return CURRENCY_CODE;
    }
}
