package com.positivity.accounting.internal.bankrec.dto;

import com.positivity.accounting.internal.bankrec.enums.BankTransactionStatus;

/**
 * The match status the F2 API serves for a statement line (story S1, #2300). Statement lines are
 * {@code bank_transaction} rows from story S1 on; the API keeps F2's two values until the review
 * read model (story S4) serves the full {@link BankTransactionStatus} set.
 */
public enum StatementLineApiStatus {
    UNMATCHED,
    MATCHED;

    /** {@code MATCHED} for a matched transaction, {@code UNMATCHED} for every other status. */
    public static StatementLineApiStatus from(BankTransactionStatus status) {
        return status == BankTransactionStatus.MATCHED ? MATCHED : UNMATCHED;
    }
}
