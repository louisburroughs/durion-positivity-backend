package com.positivity.domainevents.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.NonNull;

/**
 * Payload for {@code accounting.bankreconciliation.approved} v1 on {@code accounting.events.v1}
 * (SPEC-manual-bank-reconciliation §3.10, §4.9; story S5, #2304).
 *
 * <p>Published by pos-accounting through its transactional outbox in the transaction that approves a
 * reconciliation ({@code SUBMITTED → FINALIZED}), keyed by {@link #reconciliationId}. {@link
 * #approvedGlEndingBalance} is the live ledger balance as-of {@link #statementEndDate} that the approver
 * saw, read under the reconciliation row lock; {@link #adjustedBankBalance} is the bank side of the
 * equation at that instant. {@link #currency} states the currency of both balances (ADR-0067 R-1). No
 * consumer is named; period-close readiness reads the reconciliation itself.
 *
 * @param reconciliationId the approved reconciliation
 * @param glAccountId the reconciled ledger bank account
 * @param statementStartDate first day of the reconciled window
 * @param statementEndDate last day of the reconciled window
 * @param accountingPeriodCode {@code YYYY-MM} of the window end, for attribution
 * @param approvedGlEndingBalance the ledger balance snapshot taken at approval
 * @param adjustedBankBalance the bank side of the equation at approval
 * @param currency ISO 4217 code of both balances
 * @param approvedBy the approver (ADR-0018)
 */
public record BankReconciliationApprovedV1(
        @NonNull UUID reconciliationId,
        @NonNull UUID glAccountId,
        @NonNull LocalDate statementStartDate,
        @NonNull LocalDate statementEndDate,
        @NonNull String accountingPeriodCode,
        @NonNull BigDecimal approvedGlEndingBalance,
        @NonNull BigDecimal adjustedBankBalance,
        @NonNull String currency,
        @NonNull String approvedBy) {

    public static final String EVENT_TYPE = "accounting.bankreconciliation.approved";
    public static final int SCHEMA_VERSION = 1;

    public BankReconciliationApprovedV1 {
        if (reconciliationId == null || glAccountId == null) {
            throw new IllegalArgumentException("reconciliationId and glAccountId must not be null");
        }
        if (statementStartDate == null || statementEndDate == null) {
            throw new IllegalArgumentException("statementStartDate and statementEndDate must not be null");
        }
        if (accountingPeriodCode == null || accountingPeriodCode.isBlank()) {
            throw new IllegalArgumentException("accountingPeriodCode must not be blank");
        }
        if (approvedGlEndingBalance == null || adjustedBankBalance == null) {
            throw new IllegalArgumentException("approvedGlEndingBalance and adjustedBankBalance must not be null");
        }
        if (currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("currency must not be blank");
        }
        if (approvedBy == null || approvedBy.isBlank()) {
            throw new IllegalArgumentException("approvedBy must not be blank");
        }
    }
}
