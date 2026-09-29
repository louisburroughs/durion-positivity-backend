package com.positivity.accounting.internal.bankrec.service;

import com.positivity.accounting.internal.bankrec.entity.BankReconciliationOutstandingItem;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemSide;
import com.positivity.accounting.internal.bankrec.enums.OutstandingItemStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * The explicit equation E3 and its opening terms (SPEC-manual-bank-reconciliation §3.7; story S4, #2303),
 * as pure arithmetic over values the {@link ReconciliationCalculator} reads. F2 sign conventions: a bank
 * amount is positive for cash in; a ledger amount is debit − credit on the asset account.
 *
 * <pre>
 * adjustedBankBalance = statementClosingBalance + Σ LEDGER items open at the end of statementEndDate
 *                                               − Σ BANK items open then
 * adjustedBookBalance = glEndingBalance (live) + sumLateAdjustments
 * difference          = adjustedBankBalance − adjustedBookBalance
 * openingDifference   = statementOpeningBalance + Σ LEDGER items open at the end of statementStartDate − 1
 *                       − Σ BANK items open then − (glOpeningBalance + sumOpeningAdjustments)
 * </pre>
 */
public final class ReconciliationEquation {

    private ReconciliationEquation() {}

    /**
     * One adjustment posting on the reconciled account (§3.7): an adjustment's cash line, or once it is
     * reversed its reversal's cash line, dated at its own entry's date.
     *
     * @param ownerStatementEndDate the {@code statementEndDate} of the reconciliation that owns the adjustment
     * @param bridgesStatementId the statement a gap bridge settles, else null
     * @param reversal whether this is the reversal's line
     */
    public record Posting(
            @NonNull UUID adjustmentId,
            @NonNull UUID reconciliationId,
            @NonNull UUID journalEntryId,
            @NonNull LocalDate ownerStatementEndDate,
            @Nullable UUID bridgesStatementId,
            @NonNull LocalDate date,
            @NonNull BigDecimal amount,
            boolean reversal) {}

    /** The window E3 is computed for. */
    public record Window(
            @Nullable UUID statementId,
            @NonNull LocalDate statementStartDate,
            @NonNull LocalDate statementEndDate,
            @Nullable BigDecimal statementOpeningBalance,
            @NonNull BigDecimal statementClosingBalance) {}

    /** Every term of E3 and of the opening terms. {@code openingDifference} is null without an opening balance. */
    public record Terms(
            @NonNull BigDecimal statementClosingBalance,
            @NonNull BigDecimal sumOutstandingLedgerItems,
            @NonNull BigDecimal sumOutstandingBankItems,
            @NonNull BigDecimal adjustedBankBalance,
            @NonNull BigDecimal glEndingBalance,
            @NonNull BigDecimal sumLateAdjustments,
            @NonNull BigDecimal adjustedBookBalance,
            @NonNull BigDecimal difference,
            @Nullable BigDecimal statementOpeningBalance,
            @NonNull BigDecimal openingLedgerItems,
            @NonNull BigDecimal openingBankItems,
            @NonNull BigDecimal glOpeningBalance,
            @NonNull BigDecimal sumOpeningAdjustments,
            @Nullable BigDecimal openingDifference) {}

    /**
     * Whether an item is open at the end of {@code day} (§3.6): dated on or before it, not released, and
     * not closed on or before it. For the window being worked this equals status {@code OPEN}; it keeps
     * an old window right after later windows closed its items.
     */
    public static boolean isOpenAt(@NonNull BankReconciliationOutstandingItem item, @NonNull LocalDate day) {
        return !item.getItemDate().isAfter(day)
                && item.getStatus() != OutstandingItemStatus.RELEASED
                && (item.getClosedOn() == null || item.getClosedOn().isAfter(day));
    }

    /** The items of one side open at the end of {@code day}. */
    public static @NonNull List<BankReconciliationOutstandingItem> openAt(
            @NonNull Collection<BankReconciliationOutstandingItem> items,
            @NonNull OutstandingItemSide side,
            @NonNull LocalDate day) {
        return items.stream()
                .filter(item -> item.getSide() == side && isOpenAt(item, day))
                .toList();
    }

    /** Σ signed amounts of the items. */
    public static @NonNull BigDecimal sumItems(@NonNull Collection<BankReconciliationOutstandingItem> items) {
        return items.stream()
                .map(BankReconciliationOutstandingItem::getSignedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * The late postings of a window (§3.7): postings of adjustments owned by this or an earlier
     * reconciliation on the account (owner {@code statementEndDate} on or before this one's) dated after
     * {@code statementEndDate}.
     */
    public static @NonNull List<Posting> latePostings(
            @NonNull Collection<Posting> postings, @NonNull LocalDate statementEndDate) {
        return postings.stream()
                .filter(p -> !p.ownerStatementEndDate().isAfter(statementEndDate))
                .filter(p -> p.date().isAfter(statementEndDate))
                .toList();
    }

    /**
     * The opening postings of a window (§3.7): postings of adjustments owned by reconciliations with
     * {@code statementEndDate < statementStartDate}, plus postings of this statement's gap bridges, dated
     * on or after {@code statementStartDate}.
     */
    public static @NonNull List<Posting> openingPostings(
            @NonNull Collection<Posting> postings, @NonNull LocalDate statementStartDate, @Nullable UUID statementId) {
        return postings.stream()
                .filter(p -> !p.date().isBefore(statementStartDate))
                .filter(p -> p.ownerStatementEndDate().isBefore(statementStartDate)
                        || (statementId != null && statementId.equals(p.bridgesStatementId())))
                .toList();
    }

    /** Σ amounts of the postings. */
    public static @NonNull BigDecimal sumPostings(@NonNull Collection<Posting> postings) {
        return postings.stream().map(Posting::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * E3 and the opening terms of a window.
     *
     * @param items every outstanding item on the account that may be open at either date
     * @param postings every adjustment posting on the account
     * @param glEndingBalance the live balance at the end of {@code statementEndDate}
     * @param glOpeningBalance the live balance at the end of {@code statementStartDate − 1}
     */
    public static @NonNull Terms compute(
            @NonNull Window window,
            @NonNull Collection<BankReconciliationOutstandingItem> items,
            @NonNull Collection<Posting> postings,
            @NonNull BigDecimal glEndingBalance,
            @NonNull BigDecimal glOpeningBalance) {
        LocalDate end = window.statementEndDate();
        LocalDate dayBefore = window.statementStartDate().minusDays(1);

        BigDecimal ledgerItems = sumItems(openAt(items, OutstandingItemSide.LEDGER, end));
        BigDecimal bankItems = sumItems(openAt(items, OutstandingItemSide.BANK, end));
        BigDecimal adjustedBank =
                window.statementClosingBalance().add(ledgerItems).subtract(bankItems);
        BigDecimal sumLate = sumPostings(latePostings(postings, end));
        BigDecimal adjustedBook = glEndingBalance.add(sumLate);

        BigDecimal openingLedgerItems = sumItems(openAt(items, OutstandingItemSide.LEDGER, dayBefore));
        BigDecimal openingBankItems = sumItems(openAt(items, OutstandingItemSide.BANK, dayBefore));
        BigDecimal sumOpening =
                sumPostings(openingPostings(postings, window.statementStartDate(), window.statementId()));
        BigDecimal openingDifference = window.statementOpeningBalance() == null
                ? null
                : window.statementOpeningBalance()
                        .add(openingLedgerItems)
                        .subtract(openingBankItems)
                        .subtract(glOpeningBalance.add(sumOpening));

        return new Terms(
                window.statementClosingBalance(),
                ledgerItems,
                bankItems,
                adjustedBank,
                glEndingBalance,
                sumLate,
                adjustedBook,
                adjustedBank.subtract(adjustedBook),
                window.statementOpeningBalance(),
                openingLedgerItems,
                openingBankItems,
                glOpeningBalance,
                sumOpening,
                openingDifference);
    }

    /** Whether {@code amount} is within ± {@code tolerance} of zero. */
    public static boolean withinTolerance(@Nullable BigDecimal amount, @NonNull BigDecimal tolerance) {
        return Objects.requireNonNullElse(amount, BigDecimal.ZERO).abs().compareTo(tolerance) <= 0;
    }
}
