package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.entity.ExtInvoice;
import com.positivity.accounting.internal.enums.SourceInvoiceState;
import java.math.BigDecimal;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The one rule for the state of the invoice a payment was taken against (#2563): cases b, c, d and g
 * of {@link AutomaticPaymentApplicationService}, which decides with it when a payment settles (S2,
 * #2503), and the state the unapplied-payments list serves (S1, #2502). Both paths call these
 * functions, so the list's "invoice already paid" can never drift from the automatic decision.
 *
 * <p>The rule has two steps because automatic application checks the period and the payment between
 * them, and an invoice's balance costs queries: {@link #standing} first (b, c, d, in that order), then,
 * only for an invoice that can take the payment, {@link #byBalance} (g).
 */
final class SourceInvoiceRule {

    private SourceInvoiceRule() {}

    /**
     * Cases b, c and d, first match wins: {@link SourceInvoiceState#NOT_FOUND} when the invoice is not
     * replicated, {@link SourceInvoiceState#NOT_ELIGIBLE} when it is not {@code FINALIZED} or {@code
     * POSTED}, {@link SourceInvoiceState#OTHER_CUSTOMER} when its party, as a UUID, is missing or is not
     * {@code customerId} (BR-2, §9.5a).
     *
     * @param invoice    the replica row, or null when it is not replicated
     * @param customerId the payment's customer
     * @return the state the invoice's standing decides; null when it can take the payment, so only its
     *     balance decides ({@link #byBalance})
     */
    static @Nullable SourceInvoiceState standing(@Nullable ExtInvoice invoice, @NonNull UUID customerId) {
        if (invoice == null) {
            return SourceInvoiceState.NOT_FOUND;
        }
        if (!InvoiceBalanceCalculator.AR_ELIGIBLE_STATUSES.contains(invoice.getStatus())) {
            return SourceInvoiceState.NOT_ELIGIBLE;
        }
        if (!customerId.equals(partyUuid(invoice.getPartyId()))) {
            return SourceInvoiceState.OTHER_CUSTOMER;
        }
        return null;
    }

    /**
     * Case g, for an invoice {@link #standing} let through: {@link SourceInvoiceState#PAID} when it has no
     * open balance once rounded to the currency's scale ({@link InvoiceBalanceCalculator#isOpenReceivable},
     * the shared open-invoice rule), else {@link SourceInvoiceState#OPEN}.
     *
     * @param invoice    the replica row
     * @param balanceDue its derived balance ({@link InvoiceBalanceCalculator#balanceDue} or {@code balancesDue})
     * @param currency   the ledger currency, whose minor unit sets the rounding scale
     */
    static @NonNull SourceInvoiceState byBalance(
            @NonNull ExtInvoice invoice, @NonNull BigDecimal balanceDue, @NonNull String currency) {
        return InvoiceBalanceCalculator.isOpenReceivable(invoice, balanceDue, currency)
                ? SourceInvoiceState.OPEN
                : SourceInvoiceState.PAID;
    }

    /** The invoice's party as a UUID; null when it is missing, blank or not a UUID. */
    static @Nullable UUID partyUuid(@Nullable String partyId) {
        if (partyId == null || partyId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(partyId.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
