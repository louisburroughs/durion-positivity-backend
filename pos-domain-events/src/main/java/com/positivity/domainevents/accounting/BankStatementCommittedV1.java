package com.positivity.domainevents.accounting;

import com.positivity.domainevents.bankfeed.BankTransactionsObservedV1.SourceKind;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Payload for {@code accounting.bankstatement.committed} v1 on {@code accounting.events.v1}
 * (SPEC-manual-bank-reconciliation §3.10; story S2, #2301).
 *
 * <p>Published by pos-accounting through its transactional outbox in the transaction that commits a
 * bank statement (a manual entry or, from story S3, a file import), keyed by {@link #statementId}.
 * No consumer is named today; it is the owner fact for analytics and readiness caches (ADR-0044
 * R6). {@link #currency} states the currency of the balances (ADR-0067 R-1).
 *
 * @param statementId the committed statement
 * @param glAccountId the reconciled ledger bank account
 * @param startDate first day of the statement window
 * @param endDate last day of the statement window
 * @param openingBalance opening balance as printed by the bank
 * @param closingBalance closing balance as printed by the bank
 * @param currency ISO 4217 code of the balances
 * @param transactionCount bank transactions the statement carried
 * @param sourceKind where the statement came from
 */
public record BankStatementCommittedV1(
        @NonNull UUID statementId,
        @NonNull UUID glAccountId,
        @NonNull LocalDate startDate,
        @NonNull LocalDate endDate,
        @NonNull BigDecimal openingBalance,
        @NonNull BigDecimal closingBalance,
        @NonNull String currency,
        int transactionCount,
        @NonNull SourceKind sourceKind) {

    public static final String EVENT_TYPE = "accounting.bankstatement.committed";
    public static final int SCHEMA_VERSION = 1;

    public BankStatementCommittedV1 {
        if (statementId == null || glAccountId == null) {
            throw new IllegalArgumentException("statementId and glAccountId must not be null");
        }
        if (startDate == null || endDate == null) {
            throw new IllegalArgumentException("startDate and endDate must not be null");
        }
        if (openingBalance == null || closingBalance == null) {
            throw new IllegalArgumentException("openingBalance and closingBalance must not be null");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if (transactionCount < 0) {
            throw new IllegalArgumentException("transactionCount must not be negative");
        }
        if (sourceKind == null) {
            throw new IllegalArgumentException("sourceKind must not be null");
        }
    }
}
