package com.positivity.accounting.internal.enums;

import java.math.BigDecimal;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The side on which an account usually carries its balance (CAP:550 S35, #2524; SPEC-accounting-workspace
 * §3 P1): assets and expenses are debit-normal, liabilities, equity and revenue credit-normal. The one
 * place that rule lives; reports and ledger reads put a stored debits-minus-credits balance on the
 * account's normal side through {@link #normalBalance}.
 */
public enum NormalSide {
    DEBIT,
    CREDIT;

    /**
     * The normal side of an account type. An account whose type is unknown is read as debit-normal,
     * which is the balance as stored.
     */
    public static @NonNull NormalSide of(@Nullable AccountType accountType) {
        return accountType == AccountType.LIABILITY
                        || accountType == AccountType.EQUITY
                        || accountType == AccountType.REVENUE
                ? CREDIT
                : DEBIT;
    }

    /**
     * A stored balance (debits minus credits) expressed on this side: positive when the account holds
     * its usual balance.
     */
    public @NonNull BigDecimal normalBalance(@Nullable BigDecimal debitsMinusCredits) {
        BigDecimal balance = debitsMinusCredits != null ? debitsMinusCredits : BigDecimal.ZERO;
        return this == CREDIT ? balance.negate() : balance;
    }
}
