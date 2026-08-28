package com.positivity.accounting.internal.service;

import com.positivity.accounting.internal.dto.JournalEntryCreateRequest;
import com.positivity.accounting.internal.dto.JournalEntryResponse;
import com.positivity.accounting.internal.dto.SettlementPostingCommand;
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
     * @return posted journal entry's id
     */
    @Override
    public UUID postCreditMemoReversal(
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
    public UUID postCreditMemoReversal(
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

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();

        // Line 1: Debit Revenue (reverse revenue recognition)
        lines.add(
                lineRequest(revenueAccountId, creditAmount, BigDecimal.ZERO, "Revenue Reversal - CM#" + creditMemoId));

        // Line 2: Debit Tax Liability (reverse tax payable)
        lines.add(lineRequest(taxPayableAccountId, taxReversed, BigDecimal.ZERO, "Tax Reversal - CM#" + creditMemoId));

        // Line 3: Credit AR (reduce accounts receivable)
        lines.add(lineRequest(arAccountId, BigDecimal.ZERO, totalAmount, "AR Reduction - CM#" + creditMemoId));

        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .transactionDate(LocalDateTime.now(clock))
                .description(description + (isPriorPeriod ? " [PRIOR PERIOD: " + originalPeriodId + "]" : ""))
                .sourceEventId(creditMemoId)
                .lines(lines)
                .build();

        // Create and post entry (period gate + override applied inside post, B2)
        JournalEntryResponse created = journalEntryService.createJournalEntry(request);
        JournalEntryResponse posted =
                journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);

        log.info("Posted Credit Memo GL entry: journal entry ID {}", posted.getJournalEntryId());

        return posted.getJournalEntryId();
    }

    @Override
    public UUID postCreditMemoVoid(
            @NonNull UUID creditMemoId,
            @NonNull UUID revenueAccountId,
            @NonNull UUID taxPayableAccountId,
            @NonNull UUID arAccountId,
            @NonNull BigDecimal creditAmount,
            @NonNull BigDecimal taxReversed,
            @NonNull String description) {

        BigDecimal totalAmount = creditAmount.add(taxReversed);

        log.info(
                "Posting Credit Memo VOID GL entry {}: debit AR {}, credit revenue {}, credit tax {}",
                creditMemoId,
                totalAmount,
                creditAmount,
                taxReversed);

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();

        // Line 1: Debit AR (restore the receivable the memo had reduced)
        lines.add(lineRequest(arAccountId, totalAmount, BigDecimal.ZERO, "AR Restoration - CM VOID#" + creditMemoId));

        // Line 2: Credit Revenue (re-recognize the reversed revenue)
        lines.add(lineRequest(
                revenueAccountId, BigDecimal.ZERO, creditAmount, "Revenue Restoration - CM VOID#" + creditMemoId));

        // Line 3: Credit Sales-Tax Payable (restore the reversed tax liability)
        lines.add(lineRequest(
                taxPayableAccountId, BigDecimal.ZERO, taxReversed, "Tax Restoration - CM VOID#" + creditMemoId));

        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .transactionDate(LocalDateTime.now(clock))
                .description(description)
                .sourceEventId(creditMemoId)
                .lines(lines)
                .build();

        // Create and post entry (period gate applies inside post — voids land in an open period)
        JournalEntryResponse created = journalEntryService.createJournalEntry(request);
        JournalEntryResponse posted = journalEntryService.postJournalEntry(created.getJournalEntryId(), null);

        log.info("Posted Credit Memo VOID GL entry: journal entry ID {}", posted.getJournalEntryId());

        return posted.getJournalEntryId();
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
     * @return posted journal entry's id
     */
    @Override
    public UUID postPaymentApplication(
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
    public UUID postPaymentApplication(
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

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();

        // Debit: Undeposited Funds (D-3)
        lines.add(lineRequest(
                undepositedFundsAccountId, amount, BigDecimal.ZERO, "Cash Receipt - PA#" + paymentApplicationId));

        // Credit: AR
        lines.add(lineRequest(arAccountId, BigDecimal.ZERO, amount, "AR Reduction - PA#" + paymentApplicationId));

        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .transactionDate(transactionDate)
                .description(description)
                .sourceEventId(paymentApplicationId)
                .lines(lines)
                .build();

        JournalEntryResponse created = journalEntryService.createJournalEntry(request);
        JournalEntryResponse posted =
                journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);

        log.info("Posted payment application GL entry: journal entry ID {}", posted.getJournalEntryId());

        return posted.getJournalEntryId();
    }

    @Override
    public UUID postCustomerCreditIssuance(
            @NonNull UUID sourceEventId,
            @NonNull UUID creditId,
            @NonNull UUID undepositedFundsAccountId,
            @NonNull UUID creditLiabilityAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification) {

        log.info(
                "Posting customer credit issuance GL entry {}: debit undeposited funds {}, "
                        + "credit customer credit liability {}",
                sourceEventId,
                amount,
                amount);

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();

        // Debit: Undeposited Funds (the overpayment cash received).
        lines.add(lineRequest(
                undepositedFundsAccountId, amount, BigDecimal.ZERO, "Overpayment Cash - Credit#" + creditId));

        // Credit: Customer Credit Liability (obligation now owed to the customer).
        lines.add(lineRequest(
                creditLiabilityAccountId, BigDecimal.ZERO, amount, "Customer Credit Issued - Credit#" + creditId));

        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .transactionDate(transactionDate)
                .description(description)
                .sourceEventId(sourceEventId)
                .lines(lines)
                .build();

        JournalEntryResponse created = journalEntryService.createJournalEntry(request);
        JournalEntryResponse posted =
                journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);

        log.info("Posted customer credit issuance GL entry: journal entry ID {}", posted.getJournalEntryId());

        return posted.getJournalEntryId();
    }

    @Override
    public UUID postCustomerCreditRelief(
            @NonNull UUID sourceEventId,
            @NonNull UUID creditId,
            @NonNull UUID creditLiabilityAccountId,
            @NonNull UUID contraAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @NonNull String contraLineLabel,
            @Nullable String overrideJustification) {

        log.info(
                "Posting customer credit relief GL entry {}: debit customer credit liability {}, credit {} {}",
                sourceEventId,
                amount,
                contraLineLabel,
                amount);

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();

        // Debit: Customer Credit Liability (the obligation being discharged).
        lines.add(lineRequest(
                creditLiabilityAccountId, amount, BigDecimal.ZERO, "Customer Credit Relieved - Credit#" + creditId));

        // Credit: AR (application) or Undeposited Funds (refund) — chosen by the caller
        // through posting-category configuration, never hardcoded here.
        lines.add(lineRequest(contraAccountId, BigDecimal.ZERO, amount, contraLineLabel + " - Credit#" + creditId));

        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .transactionDate(transactionDate)
                .description(description)
                .sourceEventId(sourceEventId)
                .lines(lines)
                .build();

        JournalEntryResponse created = journalEntryService.createJournalEntry(request);
        JournalEntryResponse posted =
                journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);

        log.info("Posted customer credit relief GL entry: journal entry ID {}", posted.getJournalEntryId());

        return posted.getJournalEntryId();
    }

    @Override
    public UUID postInventoryShrinkage(
            @NonNull UUID sourceEventId,
            @NonNull UUID scrapId,
            @NonNull UUID shrinkageAccountId,
            @NonNull UUID inventoryAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification) {

        log.info(
                "Posting inventory shrinkage GL entry {}: amount {}, debit shrinkage expense account {},"
                        + " credit inventory account {}",
                sourceEventId,
                amount,
                shrinkageAccountId,
                inventoryAccountId);

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();

        // Debit: Inventory Shrinkage expense (the write-off cost recognized).
        addDebit(lines, shrinkageAccountId, amount, "Shrinkage Expense - Scrap#" + scrapId);
        // Credit: Inventory asset (the stock value leaving the balance sheet).
        addCredit(lines, inventoryAccountId, amount, "Inventory Relief - Scrap#" + scrapId);

        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .transactionDate(transactionDate)
                .description(description)
                .sourceEventId(sourceEventId)
                .lines(lines)
                .build();

        JournalEntryResponse created = journalEntryService.createJournalEntry(request);
        JournalEntryResponse posted =
                journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);

        log.info("Posted inventory shrinkage GL entry: journal entry ID {}", posted.getJournalEntryId());

        return posted.getJournalEntryId();
    }

    @Override
    public UUID postSettlement(@NonNull SettlementPostingCommand command) {
        log.info(
                "Posting settlement GL entry {}: Dr Cash {}, Dr Fees {}, Cr Undeposited {}, Cr Suspense {}",
                command.sourceEventId(),
                command.netAmount(),
                command.feeAmount(),
                command.matchedGross(),
                command.unmatchedGross());

        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();
        // Sign-route every leg so a refund/chargeback or net-negative payout never
        // produces a negative debit or credit (which would violate the
        // chk_journal_entry_line_debit_xor_credit CHECK and poison the outbox). The
        // natural side of each leg is its normal-balance side; a negative amount is
        // posted as its absolute value on the opposite side.
        //
        // Debits (natural side): net bank payout + processor fees (sum to gross).
        addSigned(lines, command.cashAccountId(), command.netAmount(), "Settlement Cash", true);
        addSigned(lines, command.feesAccountId(), command.feeAmount(), "Processor Fees", true);
        // Credits (natural side): clear matched receipts from Undeposited Funds; park
        // the rest in suspense (decision D-13). Together they equal gross, so the entry
        // balances (net + fee == gross == matchedGross + unmatchedGross).
        addSigned(
                lines, command.undepositedFundsAccountId(), command.matchedGross(), "Undeposited Funds Cleared", false);
        addSigned(lines, command.suspenseAccountId(), command.unmatchedGross(), "Settlement Suspense", false);

        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .transactionDate(command.transactionDate())
                .description(command.description())
                .sourceEventId(command.sourceEventId())
                .lines(lines)
                .build();

        JournalEntryResponse created = journalEntryService.createJournalEntry(request);
        JournalEntryResponse posted =
                journalEntryService.postJournalEntry(created.getJournalEntryId(), command.overrideJustification());

        log.info("Posted settlement GL entry: journal entry ID {}", posted.getJournalEntryId());
        return posted.getJournalEntryId();
    }

    @Override
    public UUID postSettlementWriteOff(
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
    public UUID postSettlementReclass(
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

    @Override
    public UUID postRegisterOverShort(
            @NonNull UUID sourceEventId,
            @NonNull UUID sessionId,
            @NonNull UUID debitAccountId,
            @NonNull UUID creditAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification) {
        return postTwoLineReclass(
                sourceEventId,
                debitAccountId,
                creditAccountId,
                amount,
                transactionDate,
                description,
                overrideJustification,
                "Register Over/Short - Session#" + sessionId);
    }

    /**
     * Post a balanced two-line entry {@code Dr debitAccount / Cr creditAccount}
     * of {@code amount}. Used by settlement write-off (Dr Suspense / Cr
     * Adjustment) and manual-match reclass (Dr Suspense / Cr Undeposited Funds).
     */
    private UUID postTwoLineReclass(
            @NonNull UUID sourceEventId,
            @NonNull UUID debitAccountId,
            @NonNull UUID creditAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification,
            @NonNull String lineLabel) {
        List<JournalEntryCreateRequest.JournalEntryLineRequest> lines = new ArrayList<>();
        addDebit(lines, debitAccountId, amount, lineLabel);
        addCredit(lines, creditAccountId, amount, lineLabel);

        JournalEntryCreateRequest request = JournalEntryCreateRequest.builder()
                .transactionDate(transactionDate)
                .description(description)
                .sourceEventId(sourceEventId)
                .lines(lines)
                .build();

        JournalEntryResponse created = journalEntryService.createJournalEntry(request);
        JournalEntryResponse posted =
                journalEntryService.postJournalEntry(created.getJournalEntryId(), overrideJustification);
        log.info("Posted {} GL entry: journal entry ID {}", lineLabel, posted.getJournalEntryId());
        return posted.getJournalEntryId();
    }

    private static JournalEntryCreateRequest.JournalEntryLineRequest lineRequest(
            @NonNull UUID accountId,
            @NonNull BigDecimal debitAmount,
            @NonNull BigDecimal creditAmount,
            @NonNull String description) {
        return JournalEntryCreateRequest.JournalEntryLineRequest.builder()
                .glAccountId(accountId)
                .debitAmount(debitAmount)
                .creditAmount(creditAmount)
                .description(description)
                .build();
    }

    /**
     * Append a sign-routed line unless the amount is zero (zero legs are omitted).
     * A positive amount posts to the leg's natural side; a negative amount posts its
     * absolute value to the opposite side, so no leg is ever a negative debit or
     * credit. {@code naturallyDebit} = true for normal-debit legs (Cash, Fees),
     * false for normal-credit legs (Undeposited Funds, Suspense).
     */
    private static void addSigned(
            @NonNull List<JournalEntryCreateRequest.JournalEntryLineRequest> lines,
            @NonNull UUID accountId,
            @NonNull BigDecimal amount,
            @NonNull String description,
            boolean naturallyDebit) {
        int sign = amount.signum();
        if (sign == 0) {
            return;
        }
        boolean debitSide = (sign > 0) == naturallyDebit;
        if (debitSide) {
            addDebit(lines, accountId, amount.abs(), description);
        } else {
            addCredit(lines, accountId, amount.abs(), description);
        }
    }

    /** Append a debit line unless the amount is zero (zero legs are omitted). */
    private static void addDebit(
            @NonNull List<JournalEntryCreateRequest.JournalEntryLineRequest> lines,
            @NonNull UUID accountId,
            @NonNull BigDecimal amount,
            @NonNull String description) {
        if (amount.signum() == 0) {
            return;
        }
        lines.add(lineRequest(accountId, amount, BigDecimal.ZERO, description));
    }

    /** Append a credit line unless the amount is zero (zero legs are omitted). */
    private static void addCredit(
            @NonNull List<JournalEntryCreateRequest.JournalEntryLineRequest> lines,
            @NonNull UUID accountId,
            @NonNull BigDecimal amount,
            @NonNull String description) {
        if (amount.signum() == 0) {
            return;
        }
        lines.add(lineRequest(accountId, BigDecimal.ZERO, amount, description));
    }
}
