package com.positivity.invoice.internal.enums;

import java.util.Optional;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Validated payment-terms vocabulary (#993). {@code paymentTermsCode} stays a string column on
 * {@code billing_rules}/{@code invoices} for additive growth, but every write is validated against
 * this set (API + DB CHECK). Each code is a rule — "finalization date + N calendar days" — never a
 * configured date; the per-invoice due date is computed and frozen at finalization.
 *
 * <p>Fixed-day/EOM terms and early-pay discounts (e.g. 2/10 Net 30) are out of scope for v1 —
 * they don't affect aging mechanics.
 */
public enum PaymentTerms {
    DUE_ON_RECEIPT(0),
    NET_10(10),
    NET_15(15),
    NET_30(30),
    NET_45(45),
    NET_60(60);

    private final int netDays;

    PaymentTerms(int netDays) {
        this.netDays = netDays;
    }

    /** Calendar days between the finalization date and the due date. */
    public int netDays() {
        return netDays;
    }

    /** Resolves a stored/requested code; empty for null, blank, or out-of-vocabulary values. */
    @NonNull
    public static Optional<PaymentTerms> fromCode(@Nullable String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (PaymentTerms terms : values()) {
            if (terms.name().equals(code.trim())) {
                return Optional.of(terms);
            }
        }
        return Optional.empty();
    }
}
