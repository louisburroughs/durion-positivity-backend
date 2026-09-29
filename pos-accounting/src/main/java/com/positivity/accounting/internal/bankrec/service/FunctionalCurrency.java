package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.intake.MinorUnit;
import com.positivity.accounting.internal.config.LedgerCurrency;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * The ledger currency as the bank reconciliation core sees it (SPEC D18 as amended by ADR-0067 §8
 * row A; story S2, #2301): a bank-account profile, a statement or a feed in any other currency is
 * refused with {@code CURRENCY_NOT_SUPPORTED}, and a profile the intake creates takes this currency.
 *
 * <p><b>Adapter, not a second source.</b> The code comes from {@link LedgerCurrency}, the one place
 * pos-accounting reads the ledger currency (ADR-0067 R-2); ADR-0067 step A5 swaps that for the
 * tenant's functional currency and this follows. It exists because the intake port may not depend on
 * {@code ..internal.config..} (the core walls in {@code ArchitectureTest}), and it adds the
 * minor-unit arithmetic the core needs.
 *
 * <p>{@link #tolerance()} is one minor unit of the currency (ADR-0067 §8 row A): {@code 0.01} for a
 * two-decimal currency, {@code 1} for JPY.
 */
@Component
public class FunctionalCurrency {

    private final LedgerCurrency ledgerCurrency;

    public FunctionalCurrency(@NonNull LedgerCurrency ledgerCurrency) {
        // Fails at startup on a code outside ISO 4217 rather than refusing every statement later.
        Currency.getInstance(ledgerCurrency.code());
        this.ledgerCurrency = ledgerCurrency;
    }

    /** The ISO 4217 code of the ledger currency. */
    public @NonNull String code() {
        return ledgerCurrency.code();
    }

    /** Decimal places of the ledger currency's minor unit (2 for USD, 0 for JPY). */
    public int fractionDigits() {
        return Math.max(0, Currency.getInstance(code()).getDefaultFractionDigits());
    }

    /**
     * Refuses an amount finer than the ledger currency's minor unit with 422 {@code
     * AMOUNT_PRECISION_EXCEEDS_CURRENCY} naming {@code field} (ADR-0067 PC-6, {@link MinorUnit}); never rounds. A
     * null amount passes.
     */
    public void requireMinorUnit(@Nullable BigDecimal amount, @NonNull String field) {
        if (amount != null && !MinorUnit.fits(amount, code())) {
            throw MinorUnit.exceeded(Map.of(field, MinorUnit.detail(code())));
        }
    }

    /** One minor unit of the ledger currency: the E1 tolerance. */
    public @NonNull BigDecimal tolerance() {
        return BigDecimal.ONE.movePointLeft(fractionDigits());
    }

    /** An amount for a message: at least the currency's decimals, never scientific notation. */
    public @NonNull String display(@NonNull BigDecimal amount) {
        int scale = Math.max(fractionDigits(), amount.stripTrailingZeros().scale());
        return amount.setScale(scale, RoundingMode.UNNECESSARY).toPlainString();
    }
}
