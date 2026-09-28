package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import java.util.Comparator;

/** Stable orders for the lists the reconciliation serves (ADR-0017 lists; story S4, #2303). */
final class BankRecOrdering {

    /** Bank transactions by date, then id. */
    static final Comparator<BankTransaction> BANK_TRANSACTIONS = Comparator.comparing(
                    BankTransaction::getTransactionDate)
            .thenComparing(BankTransaction::getBankTransactionId);

    /** Ledger lines by date, then id. */
    static final Comparator<LedgerLine> LEDGER_LINES =
            Comparator.comparing(LedgerLine::date).thenComparing(LedgerLine::lineId);

    private BankRecOrdering() {}
}
