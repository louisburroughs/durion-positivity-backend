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
     * Post the mirror of a Credit Memo reversal when the memo is voided (issue #997 symmetry):
     * {@code Dr AR (creditAmount + taxReversed) / Cr Revenue (creditAmount) + Cr Sales-Tax
     * Payable (taxReversed)}, dated at void time in the current open period (period gate
     * applies). Together with the T8 report's void-period restoration term this keeps GL drift
     * at zero across the POSTED → VOIDED transition without restating the posting period.
     *
     * @param creditMemoId       Voided credit memo id (source event)
     * @param revenueAccountId   Revenue account (credit side of the void)
     * @param taxPayableAccountId Sales-Tax Payable account (credit side of the void)
     * @param arAccountId        Accounts Receivable account (debit side of the void)
     * @param creditAmount       Revenue portion originally credited
     * @param taxReversed        Tax portion originally reversed
     * @param description        Journal entry description
     * @return Posted journal entry
     */
    JournalEntry postCreditMemoVoid(
            @NonNull UUID creditMemoId,
            @NonNull UUID revenueAccountId,
            @NonNull UUID taxPayableAccountId,
            @NonNull UUID arAccountId,
            @NonNull BigDecimal creditAmount,
            @NonNull BigDecimal taxReversed,
            @NonNull String description);

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
     * Post a customer-credit issuance (overpayment excess) to GL (parity-C1,
     * issue #975): {@code Dr Undeposited Funds / Cr Customer Credit Liability}
     * for the credit amount. Recognizes the overpayment cash as an asset and the
     * matching obligation to the customer as a liability, so the excess is no
     * longer a pure subledger row with no ledger linkage.
     *
     * @param sourceEventId              deterministic JE source id for the
     *                                   issuance (namespaced per credit leg so it
     *                                   never collides with the AR cash-receipt
     *                                   entry sharing the same request id)
     * @param undepositedFundsAccountId  GL account for Undeposited Funds (debit)
     * @param creditLiabilityAccountId   GL account for Customer Credit Liability
     *                                   (credit)
     * @param amount                     credit (overpayment excess) amount
     * @param transactionDate            business transaction date (the payment
     *                                   application timestamp) used as the journal
     *                                   entry date; must not be derived from
     *                                   processing/clock time so outbox retries
     *                                   post into the correct period
     * @param description                entry description
     * @param overrideJustification      optional CLOSED-period override justification
     * @return posted journal entry
     */
    JournalEntry postCustomerCreditIssuance(
            @NonNull UUID sourceEventId,
            @NonNull UUID creditId,
            @NonNull UUID undepositedFundsAccountId,
            @NonNull UUID creditLiabilityAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @Nullable String overrideJustification);

    /**
     * Post a customer-credit <em>relief</em> to GL (parity-C1 follow-on, issue
     * #992): {@code Dr Customer Credit Liability / Cr <contra>} for the drawn-down
     * amount. This is the mirror of {@link #postCustomerCreditIssuance}, which only
     * recognizes the liability; the contra account is what distinguishes the two
     * relief flavours:
     *
     * <ul>
     *   <li><b>application</b> — contra = Accounts Receivable: the credit settles a
     *       later invoice, so liability and receivable both go down;</li>
     *   <li><b>refund</b> — contra = Undeposited Funds: the liability goes down and
     *       cash goes out.</li>
     * </ul>
     *
     * <p>Both accounts are resolved by the caller through posting-category /
     * mapping-key configuration, never hardcoded. Across issuance → relief the
     * Customer Credit Liability control account nets to zero for a fully-consumed
     * credit.
     *
     * @param sourceEventId            deterministic JE source id for this relief
     *                                 (namespaced per relief type so it never
     *                                 collides with any other entry deriving from
     *                                 the same request id)
     * @param creditId                 the customer credit being relieved (audit
     *                                 label on the entry lines)
     * @param creditLiabilityAccountId GL account for Customer Credit Liability (debit)
     * @param contraAccountId          GL account credited — Accounts Receivable for an
     *                                 application, Undeposited Funds for a refund
     * @param amount                   drawn-down amount
     * @param transactionDate          business transaction date (the draw-down
     *                                 timestamp) used as the journal entry date; must
     *                                 not be derived from processing/clock time so
     *                                 outbox retries post into the correct period
     * @param description              entry description
     * @param contraLineLabel          audit label for the credited line (e.g.
     *                                 {@code "AR Reduction"} / {@code "Credit Refund"})
     * @param overrideJustification    optional CLOSED-period override justification
     * @return posted journal entry
     */
    JournalEntry postCustomerCreditRelief(
            @NonNull UUID sourceEventId,
            @NonNull UUID creditId,
            @NonNull UUID creditLiabilityAccountId,
            @NonNull UUID contraAccountId,
            @NonNull BigDecimal amount,
            @NonNull LocalDateTime transactionDate,
            @NonNull String description,
            @NonNull String contraLineLabel,
            @Nullable String overrideJustification);

    /**
     * Post an inventory shrinkage write-off to GL (odoo-parity D2, issue #1043):
     * {@code Dr Inventory Shrinkage (expense) / Cr Inventory (asset)} for
     * {@code quantity x unitCost} of a posted scrap document. Consumed from the
     * {@code inventory.scrap.posted} fact on {@code inventory.events.v1}; both
     * accounts are resolved by the caller through the {@code INVENTORY_SHRINKAGE}
     * posting category's mapping keys, never hardcoded.
     *
     * @param sourceEventId         deterministic JE source id derived from the
     *                              scrap id (namespaced so it never collides with
     *                              another entry deriving from the same id)
     * @param scrapId               the scrap document being expensed (audit label
     *                              on the entry lines)
     * @param shrinkageAccountId    GL account for Inventory Shrinkage expense (debit)
     * @param inventoryAccountId    GL account for the Inventory asset (credit)
     * @param amount                write-off amount ({@code quantity x unitCost})
     * @param transactionDate       business transaction date (the scrap's
     *                              {@code occurredAt}) used as the journal entry
     *                              date; must not be derived from processing/clock
     *                              time so Kafka redeliveries post into the correct
     *                              period
     * @param description           entry description (carries the scrap reason code)
     * @param overrideJustification optional CLOSED-period override justification
     * @return posted journal entry
     */
    JournalEntry postInventoryShrinkage(
            @NonNull UUID sourceEventId,
            @NonNull UUID scrapId,
            @NonNull UUID shrinkageAccountId,
            @NonNull UUID inventoryAccountId,
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
