package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationAdjustment;
import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.entity.BankStatement;
import com.positivity.accounting.internal.bankrec.entity.BankTransaction;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Posting;
import com.positivity.accounting.internal.bankrec.service.ReconciliationEquation.Terms;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * A reconciliation read live (SPEC §3.7; story S4, #2303): E3 and the opening terms with every term's
 * drill-down, the window's baseline, and the unexplained items from the baseline on (E4 inputs).
 *
 * @param baselineStatement the acknowledged statement whose start is the window's baseline; null when the
 *     account has none (no lower bound)
 * @param ledgerItemsOpenAtEnd / bankItemsOpenAtEnd the items in E3's closing terms
 * @param ledgerItemsOpenAtStart / bankItemsOpenAtStart the items in the opening terms
 * @param agedItemsAwaitingReaffirmation aged {@code OTHER_LEDGER_TIMING} items not reaffirmed in this
 *     reconciliation, counted as unexplained ledger lines (§3.6)
 * @param bridges the gap bridges of this window's statement, any status
 */
public record ReconciliationSnapshot(
        @NonNull Terms terms,
        @Nullable BankStatement baselineStatement,
        @NonNull List<BankReconciliationOutstandingItem> ledgerItemsOpenAtEnd,
        @NonNull List<BankReconciliationOutstandingItem> bankItemsOpenAtEnd,
        @NonNull List<BankReconciliationOutstandingItem> ledgerItemsOpenAtStart,
        @NonNull List<BankReconciliationOutstandingItem> bankItemsOpenAtStart,
        @NonNull List<Posting> latePostings,
        @NonNull List<Posting> openingPostings,
        @NonNull List<BankTransaction> unexplainedBank,
        @NonNull List<LedgerLine> unexplainedLedger,
        @NonNull List<BankReconciliationOutstandingItem> agedItemsAwaitingReaffirmation,
        @NonNull List<BankReconciliationAdjustment> bridges) {

    /** The window's baseline date (§3.1), or null without one. */
    public @Nullable LocalDate baselineDate() {
        return baselineStatement != null ? baselineStatement.getStartDate() : null;
    }

    /** {@code countUnexplainedBank}: exact, never toleranced (§3.7). */
    public int countUnexplainedBank() {
        return unexplainedBank.size();
    }

    public @NonNull BigDecimal sumUnexplainedBank() {
        return unexplainedBank.stream().map(BankTransaction::getSignedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** {@code countUnexplainedLedger}: unexplained lines plus aged items awaiting reaffirmation (§3.6). */
    public int countUnexplainedLedger() {
        return unexplainedLedger.size() + agedItemsAwaitingReaffirmation.size();
    }

    public @NonNull BigDecimal sumUnexplainedLedger() {
        BigDecimal lines =
                unexplainedLedger.stream().map(LedgerLine::signedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return lines.add(ReconciliationEquation.sumItems(agedItemsAwaitingReaffirmation));
    }
}
