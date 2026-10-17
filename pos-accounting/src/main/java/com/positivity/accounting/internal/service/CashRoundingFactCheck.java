package com.positivity.accounting.internal.service;

import com.positivity.domainevents.payment.CashRoundingTriple;
import com.positivity.domainevents.payment.PaymentSettledV1;
import java.math.BigDecimal;
import java.util.Optional;
import org.jspecify.annotations.NonNull;

/**
 * ADR-0067 A8 (CAP:550 #2662, item 12, AC 20): pos-accounting validates the cash-rounding triple on a settled fact
 * before it records or posts anything. The rule itself lives beside the fact ({@link CashRoundingTriple}) so the
 * publisher and this consumer cannot drift apart.
 */
final class CashRoundingFactCheck {

    private CashRoundingFactCheck() {}

    /** The rounding to post (signed, zero when none). */
    static @NonNull BigDecimal roundingOf(@NonNull PaymentSettledV1 fact) {
        return CashRoundingTriple.roundingOrZero(fact);
    }

    /** Why the triple does not hold together, or empty when it does (or there is none). */
    static @NonNull Optional<String> problem(@NonNull PaymentSettledV1 fact) {
        return CashRoundingTriple.problem(fact);
    }
}
