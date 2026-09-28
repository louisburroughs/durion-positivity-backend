package com.positivity.accounting.internal.config;

import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The currency the ledger books in: the one place pos-accounting reads it, so ADR-0067 step A5
 * replaces it with the tenant's functional currency at once. Read from
 * {@code accounting.ledger.base-currency}, the property the settlement guard already uses
 * ({@code SettlementReconciliationServiceImpl}).
 *
 * <p>A Stage A ledger books its own currency only (ADR-0067 PC-9): a document or fact in another
 * currency is never booked at par. Inbound facts are held visibly with a currency reason; requests
 * are refused. Until every producer stamps a currency (ADR-0067 E-3), an absent currency means the
 * ledger currency, so {@link #isForeign} treats {@code null} as not foreign.
 */
@Component
public class LedgerCurrency {

    private final String code;

    public LedgerCurrency(@Value("${accounting.ledger.base-currency}") @NonNull String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalStateException(
                    "accounting.ledger.base-currency must be a three-letter ISO 4217 code, was '" + code + "'");
        }
        this.code = normalized;
    }

    /** The ledger's ISO 4217 currency code, upper case. */
    @NonNull
    public String code() {
        return code;
    }

    /**
     * Whether an amount stated in {@code currency} is in a currency other than the ledger's. A
     * blank or absent currency is not foreign (ADR-0067 E-3).
     */
    public boolean isForeign(@Nullable String currency) {
        return currency != null && !currency.isBlank() && !code.equalsIgnoreCase(currency.trim());
    }
}
