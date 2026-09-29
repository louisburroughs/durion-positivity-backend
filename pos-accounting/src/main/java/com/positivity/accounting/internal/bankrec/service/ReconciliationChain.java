package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliation;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The contiguous chain of FINALIZED reconciliations from a baseline (SPEC §4.1, §5.3; stories S2 #2301, S6
 * #2305): windows that end before the baseline are skipped; the chain stops at the first gap. Its last member's
 * {@code statementEndDate} is the account's {@code reconciledFrontier}. The bank-accounts read passes the current
 * baseline, close readiness the baseline that applies at the period end.
 */
public final class ReconciliationChain {

    private ReconciliationChain() {}

    /**
     * The chain members in order.
     *
     * @param finalized the account's FINALIZED reconciliations ordered by {@code statementStartDate}
     * @param baseline the baseline the chain starts from; null starts it at the first window
     */
    public static @NonNull List<BankReconciliation> members(
            @NonNull List<BankReconciliation> finalized, @Nullable LocalDate baseline) {
        LocalDate next = baseline;
        List<BankReconciliation> chain = new ArrayList<>();
        for (BankReconciliation reconciliation : finalized) {
            LocalDate start = reconciliation.getStatementStartDate();
            LocalDate end = reconciliation.getStatementEndDate();
            if (start == null || end == null || (next != null && end.isBefore(next))) {
                continue;
            }
            if (next != null && start.isAfter(next)) {
                break;
            }
            chain.add(reconciliation);
            next = end.plusDays(1);
        }
        return chain;
    }

    /** The end of the chain (the {@code reconciledFrontier}), or null when it is empty. */
    public static @Nullable LocalDate frontier(
            @NonNull List<BankReconciliation> finalized, @Nullable LocalDate baseline) {
        List<BankReconciliation> chain = members(finalized, baseline);
        return chain.isEmpty() ? null : chain.getLast().getStatementEndDate();
    }
}
