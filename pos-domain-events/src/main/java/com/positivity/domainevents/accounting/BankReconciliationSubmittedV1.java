package com.positivity.domainevents.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Payload for {@code accounting.bankreconciliation.submitted} v1 on {@code accounting.events.v1}
 * (SPEC-manual-bank-reconciliation §3.10; story S5, #2304).
 *
 * <p>Published by pos-accounting through its transactional outbox in the transaction that moves a
 * reconciliation {@code IN_PROGRESS → SUBMITTED}, keyed by {@link #reconciliationId}. The gate E4 held
 * at submission, so both counts are zero today; they are carried so a consumer never re-derives them.
 * {@link #currency} states the currency of {@link #difference} (ADR-0067 R-1). No consumer is named.
 *
 * @param reconciliationId the submitted reconciliation
 * @param glAccountId the reconciled ledger bank account
 * @param statementEndDate last day of the reconciled window
 * @param difference {@code adjustedBankBalance − adjustedBookBalance} at submission
 * @param currency ISO 4217 code of {@link #difference}
 * @param countUnexplainedBank unexplained bank transactions from the baseline to the window end
 * @param countUnexplainedLedger unexplained ledger lines from the baseline to the window end
 * @param submittedBy the preparer (ADR-0018)
 */
public record BankReconciliationSubmittedV1(
        @NonNull UUID reconciliationId,
        @NonNull UUID glAccountId,
        @NonNull LocalDate statementEndDate,
        @NonNull BigDecimal difference,
        @NonNull String currency,
        int countUnexplainedBank,
        int countUnexplainedLedger,
        @NonNull String submittedBy) {

    public static final String EVENT_TYPE = "accounting.bankreconciliation.submitted";
    public static final int SCHEMA_VERSION = 1;

    public BankReconciliationSubmittedV1 {
        if (reconciliationId == null || glAccountId == null) {
            throw new IllegalArgumentException("reconciliationId and glAccountId must not be null");
        }
        if (statementEndDate == null) {
            throw new IllegalArgumentException("statementEndDate must not be null");
        }
        if (difference == null) {
            throw new IllegalArgumentException("difference must not be null");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if (countUnexplainedBank < 0 || countUnexplainedLedger < 0) {
            throw new IllegalArgumentException("unexplained counts must not be negative");
        }
        if (submittedBy == null || submittedBy.isBlank()) {
            throw new IllegalArgumentException("submittedBy must not be blank");
        }
    }
}
