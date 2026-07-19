package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.SettlementPostingCommand;
import com.positivity.accounting.internal.entity.JournalEntry;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public interface GLPostingService {

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
    JournalEntry postCreditMemoReversal(
            UUID creditMemoId,
            UUID revenueAccountId,
            UUID taxPayableAccountId,
            UUID arAccountId,
            BigDecimal creditAmount,
            BigDecimal taxReversed,
            String description,
            boolean isPriorPeriod,
            String originalPeriodId);

    /**
     * Post a Credit Memo reversal to GL with an optional accounting-period
     * override (story B2, issue #944). Behaves like the overload without
     * {@code overrideJustification}; the justification is threaded to
     * {@link JournalEntryService#postJournalEntry(UUID, String)} so a caller
     * holding {@code accounting:period:override} may post into a CLOSED
     * period (audit-logged). Hard-locked dates are always rejected.
     *
     * @param overrideJustification optional justification for posting into a
     *                              CLOSED period
     * @return Posted journal entry
     */
    JournalEntry postCreditMemoReversal(
            @NonNull UUID creditMemoId,
            @NonNull UUID revenueAccountId,
            @NonNull UUID taxPayableAccountId,
            @NonNull UUID arAccountId,
            @NonNull BigDecimal creditAmount,
            @NonNull BigDecimal taxReversed,
            @NonNull String description,
            boolean isPriorPeriod,
            @Nullable String originalPeriodId,
            @Nullable String overrideJustification);

    /**
     * Post a payment application (AR cash receipt) to GL.
     *
     * Creates journal entry with:
     * - Debit: Undeposited Funds (decision D-3 — cash receipts are never
     * posted straight to Cash; settlement reconciliation clears Undeposited
     * Funds to Cash later)
     * - Credit: Accounts Receivable
     *
     * @param paymentApplicationId       Payment application request ID (source
     *                                   event)
     * @param undepositedFundsAccountId  GL account for Undeposited Funds
     *                                   (debit side)
     * @param arAccountId                GL account for AR
     * @param amount                     Payment amount
     * @param transactionDate            Business transaction date (the payment
     *                                   application timestamp) used as the
     *                                   journal entry date; must not be derived
     *                                   from processing/clock time so outbox
     *                                   retries post into the correct period
     * @param description                Entry description
     * @return Posted journal entry
     */
    JournalEntry postPaymentApplication(
            UUID paymentApplicationId,
            UUID undepositedFundsAccountId,
            UUID arAccountId,
            BigDecimal amount,
            LocalDateTime transactionDate,
            String description);

    /**
     * Post a payment application to GL with an optional accounting-period
     * override (story B2, issue #944). Behaves like the overload without
     * {@code overrideJustification}; the justification is threaded to
     * {@link JournalEntryService#postJournalEntry(UUID, String)} so a caller
     * holding {@code accounting:period:override} may post into a CLOSED
     * period (audit-logged). Hard-locked dates are always rejected.
     *
     * @param overrideJustification optional justification for posting into a
     *                              CLOSED period
     * @return Posted journal entry
     */
    JournalEntry postPaymentApplication(
            @NonNull UUID paymentApplicationId,
            @NonNull UUID undepositedFundsAccountId,
            @NonNull UUID arAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification);

    /**
     * Post the batched settlement journal entry (story F1c, issue #963, decision
     * D-13): {@code Dr Cash (net) / Dr Processor Fees (fee) / Cr Undeposited
     * Funds (matched gross) / Cr Settlement Suspense (unmatched gross)}. Zero
     * legs are omitted; the entry always balances by the header invariant. The
     * period gate (story B2) applies inside posting.
     *
     * @param command settlement posting command
     * @return posted journal entry
     */
    JournalEntry postSettlement(@NonNull SettlementPostingCommand command);

    /**
     * Post a reversible settlement write-off (story F1c, decision D-14): {@code
     * Dr Settlement Suspense / Cr Settlement Adjustment}, clearing an unmatched
     * line's parked gross out of suspense. Only used below the configured
     * write-off threshold; a reversible entry, never a silent status flip.
     *
     * @param sourceEventId deterministic JE source id for the write-off
     * @param suspenseAccountId settlement suspense account (debit)
     * @param adjustmentAccountId settlement adjustment account (credit)
     * @param amount write-off amount (the line gross)
     * @param transactionDate journal entry date
     * @param description entry description
     * @param overrideJustification optional CLOSED-period override justification
     * @return posted journal entry
     */
    JournalEntry postSettlementWriteOff(
            @NonNull UUID sourceEventId,
            @NonNull UUID suspenseAccountId,
            @NonNull UUID adjustmentAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification);

    /**
     * Post a settlement reclass when an unmatched line is manually matched
     * (story F1c, decision D-13): {@code Dr Settlement Suspense / Cr Undeposited
     * Funds}, moving the parked gross to the clearing account matched receipts
     * use so suspense trends to zero.
     *
     * @param sourceEventId deterministic JE source id for the reclass
     * @param suspenseAccountId settlement suspense account (debit)
     * @param undepositedFundsAccountId undeposited funds clearing account (credit)
     * @param amount reclass amount (the line gross)
     * @param transactionDate journal entry date
     * @param description entry description
     * @param overrideJustification optional CLOSED-period override justification
     * @return posted journal entry
     */
    JournalEntry postSettlementReclass(
            @NonNull UUID sourceEventId,
            @NonNull UUID suspenseAccountId,
            @NonNull UUID undepositedFundsAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification);
}
