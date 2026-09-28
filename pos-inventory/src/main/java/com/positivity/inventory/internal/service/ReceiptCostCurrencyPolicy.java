package com.positivity.inventory.internal.service;

import java.util.Currency;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Decides whether a receipt may take its document cost from its purchase order (ADR-0067 DF-6,
 * #2314). Inventory cost has no currency of its own: {@code inventory_ledger_entry.unit_cost} and
 * {@code sku_cost_state} are read as the functional currency. A cost quoted in any other currency
 * would move the SKU's cost by a figure in the wrong unit, so until a conversion exists (ADR-0067
 * PC-9, PC-13, PC-17) such a receipt posts its quantity with no document cost, and the row is
 * flagged with the reason this policy gives.
 *
 * <p>The functional currency is the value accounting's ledger is configured with,
 * {@code accounting.ledger.base-currency}, read under the same key rather than restated here. It
 * is an interim source until the shared tenant accessor of ADR-0067 PC-2 (steps A1/A2) replaces
 * both.
 */
@Component
public class ReceiptCostCurrencyPolicy {

    /** Marker that opens every reason, so a flagged ledger row can be found by its notes. */
    public static final String AWAITING_COST = "AWAITING_COST";

    private final String functionalCurrency;

    public ReceiptCostCurrencyPolicy(@Value("${accounting.ledger.base-currency}") String functionalCurrency) {
        String canonical = canonicalIsoCode(functionalCurrency);
        if (canonical == null) {
            throw new IllegalStateException("accounting.ledger.base-currency must be an ISO 4217 currency code, was ["
                    + functionalCurrency + "]");
        }
        this.functionalCurrency = canonical;
    }

    /**
     * Why a receipt against a document in {@code documentCurrency} may not take its cost, or
     * empty when it may. A missing or unrecognised currency is never assumed to be the functional
     * one.
     */
    public @NonNull Optional<String> awaitingCostReason(@Nullable String documentCurrency) {
        if (functionalCurrency.equals(canonicalIsoCode(documentCurrency))) {
            return Optional.empty();
        }
        String stated = documentCurrency == null || documentCurrency.isBlank() ? "(none)" : documentCurrency.trim();
        return Optional.of(AWAITING_COST + ": purchase order currency " + stated + " is not the functional currency "
                + functionalCurrency + "; quantity posted without a document cost until it is converted"
                + " (ADR-0067 DF-6)");
    }

    private static @Nullable String canonicalIsoCode(@Nullable String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return Currency.getInstance(code.trim().toUpperCase(Locale.ROOT)).getCurrencyCode();
        } catch (IllegalArgumentException notIso) {
            return null;
        }
    }
}
