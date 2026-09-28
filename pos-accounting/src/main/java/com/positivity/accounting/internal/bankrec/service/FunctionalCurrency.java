package com.positivity.accounting.internal.bankrec.service;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Locale;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The currency of the tenant's ledger as the bank reconciliation core sees it (SPEC D18 as amended
 * by ADR-0067 §8 row A; story S2, #2301): a bank-account profile, a statement or a feed in any other
 * currency is refused with {@code CURRENCY_NOT_SUPPORTED}, and a profile the intake creates takes
 * this currency.
 *
 * <p><b>Interim seam.</b> ADR-0067 makes the functional currency a tenant attribute mastered by
 * pos-tenant and read through a pos-tenancy-common accessor over each module's replica (PC-2). That
 * accessor does not exist yet, so this reads the module's one existing ledger-currency setting,
 * {@code accounting.ledger.base-currency} (the same one {@code SettlementReconciliationServiceImpl}
 * reads), and is the only place the bank reconciliation core learns a currency. When the accessor
 * lands, only this class changes.
 *
 * <p>{@link #tolerance()} is one minor unit of the currency (ADR-0067 §8 row A): {@code 0.01} for a
 * two-decimal currency, {@code 1} for JPY.
 */
@Component
public class FunctionalCurrency {

    private final String code;

    public FunctionalCurrency(@Value("${accounting.ledger.base-currency:USD}") String configured) {
        String upper = configured.trim().toUpperCase(Locale.ROOT);
        // Fails at startup on a code outside ISO 4217 rather than refusing every statement later.
        Currency.getInstance(upper);
        this.code = upper;
    }

    /** The ISO 4217 code of the ledger currency. */
    public @NonNull String code() {
        return code;
    }

    /** Decimal places of the ledger currency's minor unit (2 for USD, 0 for JPY). */
    public int fractionDigits() {
        return Math.max(0, Currency.getInstance(code).getDefaultFractionDigits());
    }

    /** One minor unit of the ledger currency: the E1 tolerance. */
    public @NonNull BigDecimal tolerance() {
        return BigDecimal.ONE.movePointLeft(fractionDigits());
    }

    /** An amount for a message: at least the currency's decimals, never scientific notation. */
    public @NonNull String display(@NonNull BigDecimal amount) {
        int scale = Math.max(fractionDigits(), amount.stripTrailingZeros().scale());
        return amount.setScale(scale, java.math.RoundingMode.UNNECESSARY).toPlainString();
    }
}
