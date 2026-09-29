package com.positivity.accounting.internal.exception;

import java.util.List;
import java.util.UUID;

/**
 * A close refused because in-scope bank accounts are not reconciled under a {@code REQUIRED*} bank reconciliation
 * close policy (SPEC-manual-bank-reconciliation §5.2, §5.9; story S6, #2305): 422 {@code
 * PERIOD_BANK_RECONCILIATION_INCOMPLETE}, one {@code fieldErrors[unreconciledGlAccountIds]} entry per blocked
 * account — the {@code PERIOD_HAS_DRAFT_ENTRIES} shape — plus {@code fieldErrors[bankReconciliationException]}
 * when the policy refused an exception the caller asked for.
 */
public class PeriodBankReconciliationIncompleteException extends PeriodCloseBlockedException {

    /** One blocked account with the codes of its BLOCKING checks. */
    public record UnreconciledAccount(UUID glAccountId, String accountCode, List<String> checkCodes) {

        public UnreconciledAccount {
            checkCodes = List.copyOf(checkCodes);
        }
    }

    private final List<UnreconciledAccount> unreconciledAccounts;
    private final String refusedExceptionReason;

    /**
     * @param refusedExceptionReason why the policy refused an exception the caller asked for; null when none was
     *     asked for
     */
    public PeriodBankReconciliationIncompleteException(
            String periodCode, List<UnreconciledAccount> unreconciledAccounts, String refusedExceptionReason) {
        super(
                periodCode,
                "Cannot close period " + periodCode + ": " + unreconciledAccounts.size()
                        + " bank account(s) are not reconciled under the bank reconciliation close policy");
        this.unreconciledAccounts = List.copyOf(unreconciledAccounts);
        this.refusedExceptionReason = refusedExceptionReason;
    }

    public List<UnreconciledAccount> getUnreconciledAccounts() {
        return unreconciledAccounts;
    }

    /** Why the exception was refused, or null when none was asked for. */
    public String getRefusedExceptionReason() {
        return refusedExceptionReason;
    }
}
