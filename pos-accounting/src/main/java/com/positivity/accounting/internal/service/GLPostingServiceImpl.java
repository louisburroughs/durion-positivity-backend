package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.SettlementPostingCommand;
import com.positivity.accounting.internal.entity.JournalEntry;
import com.positivity.accounting.internal.entity.JournalEntryLine;
import com.positivity.accounting.service.GLPostingService;
import com.positivity.accounting.service.JournalEntryService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for posting GL entries related to accounting transactions.
 *
 * Responsibilities:
 * - Create and post journal entries for Credit Memos, Payment Applications,
 * etc.
 * - Validate entry balance before posting
 * - Link journal entries to source events
 * - Support prior period adjustment flagging
 *
 * Design:
 * - Wraps JournalEntryService for domain-specific GL posting
 * - Ensures all GL entries are balanced and posted atomically
 * - Provides helper methods for common posting patterns
 *
 * @see JournalEntryServiceImpl
 * @see <a href=
 *      "https://github.com/louisburroughs/durion-positivity-backend/issues/131">Issue
 *      #131</a>
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
public class GLPostingServiceImpl implements GLPostingService {
    private final Clock clock;

    private final JournalEntryService journalEntryService;

    /**
     * Post a Credit Memo reversal to GL.
     *
     * Creates journal entry with:
     * - Debit: Revenue (credit amount)
     * - Debit: Tax Liability (tax reversed)
     * - Credit: Accounts Receivable (total amount)
     *
     * @param creditMemoId        Credit Memo identifier (source event)
     * @param revenueAccountId    GL account for revenue reversal
     * @param taxPayableAccountId GL account for tax reversal
     * @param arAccountId         GL account for AR reduction
     * @param creditAmount        Credit amount (revenue portion)
     * @param taxReversed         Tax amount reversed
     * @param description         Entry description
     * @param isPriorPeriod       True if prior period adjustment
     * @param originalPeriodId    Original period ID if prior period
     * @return Posted journal entry
     */
    @Override
    public JournalEntry postCreditMemoReversal(
            @NonNull UUID creditMemoId,
            @NonNull UUID revenueAccountId,
            @NonNull UUID taxPayableAccountId,
            @NonNull UUID arAccountId,
            @NonNull BigDecimal creditAmount,
            @NonNull BigDecimal taxReversed,
            @NonNull String description,
            boolean isPriorPeriod,
            String originalPeriodId) {
        return postCreditMemoReversal(
                creditMemoId,
                revenueAccountId,
                taxPayableAccountId,
                arAccountId,
                creditAmount,
                taxReversed,
                description,
                isPriorPeriod,
                originalPeriodId,
                null);
    }

    @Override
    public JournalEntry postCreditMemoReversal(
            @NonNull UUID creditMemoId,
            @NonNull UUID revenueAccountId,
            @NonNull UUID taxPayableAccountId,
            @NonNull UUID arAccountId,
            @NonNull BigDecimal creditAmount,
            @NonNull BigDecimal taxReversed,
            @NonNull String description,
            boolean isPriorPeriod,
            String originalPeriodId,
            @Nullable String overrideJustification) {

        BigDecimal totalAmount = creditAmount.add(taxReversed);

        log.info(
                "Posting Credit Memo GL entry {}: debit revenue {}, debit tax {}, credit AR {}",
                creditMemoId,
                creditAmount,
                taxReversed,
                totalAmount);

        // Create journal entry
        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(LocalDateTime.now(clock));
        entry.setDescription(description + (isPriorPeriod ? " [PRIOR PERIOD: " + originalPeriodId + "]" : ""));
        entry.setSourceEventId(creditMemoId);

        // Create journal entry lines
        List<JournalEntryLine> lines = new ArrayList<>();

        // Line 1: Debit Revenue (reverse revenue recognition)
        JournalEntryLine revenueLine = new JournalEntryLine();
        revenueLine.setGlAccountId(revenueAccountId);
        revenueLine.setDebitAmount(creditAmount);
        revenueLine.setCreditAmount(BigDecimal.ZERO);
        revenueLine.setDescription("Revenue Reversal - CM#" + creditMemoId);
        lines.add(revenueLine);

        // Line 2: Debit Tax Liability (reverse tax payable)
        JournalEntryLine taxLine = new JournalEntryLine();
        taxLine.setGlAccountId(taxPayableAccountId);
        taxLine.setDebitAmount(taxReversed);
        taxLine.setCreditAmount(BigDecimal.ZERO);
        taxLine.setDescription("Tax Reversal - CM#" + creditMemoId);
        lines.add(taxLine);

        // Line 3: Credit AR (reduce accounts receivable)
        JournalEntryLine arLine = new JournalEntryLine();
        arLine.setGlAccountId(arAccountId);
        arLine.setDebitAmount(BigDecimal.ZERO);
        arLine.setCreditAmount(totalAmount);
        arLine.setDescription("AR Reduction - CM#" + creditMemoId);
        lines.add(arLine);

        entry.setLines(lines);

        // Create and post entry (period gate + override applied inside post, B2)
        JournalEntry created = journalEntryService.createJournalEntry(entry);
        JournalEntry posted = journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);

        log.info("Posted Credit Memo GL entry: journal entry ID {}", posted.getJournalEntryId());

        return posted;
    }

    /**
     * Post a payment application (AR cash receipt) to GL.
     *
     * Creates journal entry with:
     * - Debit: Undeposited Funds (decision D-3 — never straight to Cash)
     * - Credit: Accounts Receivable
     *
     * @param paymentApplicationId      Payment application request ID (source
     *                                  event)
     * @param undepositedFundsAccountId GL account for Undeposited Funds (debit
     *                                  side)
     * @param arAccountId               GL account for AR
     * @param amount                    Payment amount
     * @param description               Entry description
     * @return Posted journal entry
     */
    @Override
    public JournalEntry postPaymentApplication(
            @NonNull UUID paymentApplicationId,
            @NonNull UUID undepositedFundsAccountId,
            @NonNull UUID arAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description) {
        return postPaymentApplication(
                paymentApplicationId,
                undepositedFundsAccountId,
                arAccountId,
                amount,
                transactionDate,
                description,
                null);
    }

    @Override
    public JournalEntry postPaymentApplication(
            @NonNull UUID paymentApplicationId,
            @NonNull UUID undepositedFundsAccountId,
            @NonNull UUID arAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification) {

        log.info(
                "Posting payment application GL entry {}: debit undeposited funds {}, credit AR {}",
                paymentApplicationId,
                amount,
                amount);

        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(transactionDate);
        entry.setDescription(description);
        entry.setSourceEventId(paymentApplicationId);

        List<JournalEntryLine> lines = new ArrayList<>();

        // Debit: Undeposited Funds (D-3)
        JournalEntryLine cashLine = new JournalEntryLine();
        cashLine.setGlAccountId(undepositedFundsAccountId);
        cashLine.setDebitAmount(amount);
        cashLine.setCreditAmount(BigDecimal.ZERO);
        cashLine.setDescription("Cash Receipt - PA#" + paymentApplicationId);
        lines.add(cashLine);

        // Credit: AR
        JournalEntryLine arLine = new JournalEntryLine();
        arLine.setGlAccountId(arAccountId);
        arLine.setDebitAmount(BigDecimal.ZERO);
        arLine.setCreditAmount(amount);
        arLine.setDescription("AR Reduction - PA#" + paymentApplicationId);
        lines.add(arLine);

        entry.setLines(lines);

        JournalEntry created = journalEntryService.createJournalEntry(entry);
        JournalEntry posted = journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);

        log.info("Posted payment application GL entry: journal entry ID {}", posted.getJournalEntryId());

        return posted;
    }

    @Override
    public JournalEntry postSettlement(@NonNull SettlementPostingCommand command) {
        log.info(
                "Posting settlement GL entry {}: Dr Cash {}, Dr Fees {}, Cr Undeposited {}, Cr Suspense {}",
                command.sourceEventId(),
                command.netAmount(),
                command.feeAmount(),
                command.matchedGross(),
                command.unmatchedGross());

        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(command.transactionDate());
        entry.setDescription(command.description());
        entry.setSourceEventId(command.sourceEventId());

        List<JournalEntryLine> lines = new ArrayList<>();
        // Debits: net bank payout + processor fees (sum to gross).
        addDebit(lines, command.cashAccountId(), command.netAmount(), "Settlement Cash");
        addDebit(lines, command.feesAccountId(), command.feeAmount(), "Processor Fees");
        // Credits: clear matched receipts from Undeposited Funds; park the rest
        // in suspense (decision D-13). Together they equal gross, so the entry
        // balances (net + fee == gross == matchedGross + unmatchedGross).
        addCredit(lines, command.undepositedFundsAccountId(), command.matchedGross(), "Undeposited Funds Cleared");
        addCredit(lines, command.suspenseAccountId(), command.unmatchedGross(), "Settlement Suspense");

        entry.setLines(lines);

        JournalEntry created = journalEntryService.createJournalEntry(entry);
        JournalEntry posted =
                journalEntryService.postJournalEntry(created.getJournalEntryId(), command.overrideJustification());

        log.info("Posted settlement GL entry: journal entry ID {}", posted.getJournalEntryId());
        return posted;
    }

    @Override
    public JournalEntry postSettlementWriteOff(
            @NonNull UUID sourceEventId,
            @NonNull UUID suspenseAccountId,
            @NonNull UUID adjustmentAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification) {
        return postTwoLineReclass(
                sourceEventId,
                suspenseAccountId,
                adjustmentAccountId,
                amount,
                transactionDate,
                description,
                overrideJustification,
                "Settlement Write-off");
    }

    @Override
    public JournalEntry postSettlementReclass(
            @NonNull UUID sourceEventId,
            @NonNull UUID suspenseAccountId,
            @NonNull UUID undepositedFundsAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification) {
        return postTwoLineReclass(
                sourceEventId,
                suspenseAccountId,
                undepositedFundsAccountId,
                amount,
                transactionDate,
                description,
                overrideJustification,
                "Settlement Reclass");
    }

    /**
     * Post a balanced two-line entry {@code Dr debitAccount / Cr creditAccount}
     * of {@code amount}. Used by settlement write-off (Dr Suspense / Cr
     * Adjustment) and manual-match reclass (Dr Suspense / Cr Undeposited Funds).
     */
    private JournalEntry postTwoLineReclass(
            @NonNull UUID sourceEventId,
            @NonNull UUID debitAccountId,
            @NonNull UUID creditAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification,
            @NonNull String lineLabel) {
        JournalEntry entry = new JournalEntry();
        entry.setTransactionDate(transactionDate);
        entry.setDescription(description);
        entry.setSourceEventId(sourceEventId);

        List<JournalEntryLine> lines = new ArrayList<>();
        addDebit(lines, debitAccountId, amount, lineLabel);
        addCredit(lines, creditAccountId, amount, lineLabel);
        entry.setLines(lines);

        JournalEntry created = journalEntryService.createJournalEntry(entry);
        JournalEntry posted = journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);
        log.info("Posted {} GL entry: journal entry ID {}", lineLabel, posted.getJournalEntryId());
        return posted;
    }

    /** Append a debit line unless the amount is zero (zero legs are omitted). */
    private static void addDebit(
            @NonNull List<JournalEntryLine> lines,
            @NonNull UUID accountId,
            @NonNull BigDecimal amount,
            @NonNull String description) {
        if (amount.signum() == 0) {
            return;
        }
        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccountId(accountId);
        line.setDebitAmount(amount);
        line.setCreditAmount(BigDecimal.ZERO);
        line.setDescription(description);
        lines.add(line);
    }

    /** Append a credit line unless the amount is zero (zero legs are omitted). */
    private static void addCredit(
            @NonNull List<JournalEntryLine> lines,
            @NonNull UUID accountId,
            @NonNull BigDecimal amount,
            @NonNull String description) {
        if (amount.signum() == 0) {
            return;
        }
        JournalEntryLine line = new JournalEntryLine();
        line.setGlAccountId(accountId);
        line.setDebitAmount(BigDecimal.ZERO);
        line.setCreditAmount(amount);
        line.setDescription(description);
        lines.add(line);
    }
}
