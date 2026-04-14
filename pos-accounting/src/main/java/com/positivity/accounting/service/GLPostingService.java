package com.positivity.accounting.service;

import com.positivity.accounting.internal.entity.JournalEntry;
import java.math.BigDecimal;
import java.util.UUID;

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
     * Post a payment application to GL.
     *
     * Creates journal entry with:
     * - Debit: Cash/Bank
     * - Credit: Accounts Receivable
     *
     * @param paymentApplicationId Payment application ID (source event)
     * @param cashAccountId        GL account for cash/bank
     * @param arAccountId          GL account for AR
     * @param amount               Payment amount
     * @param description          Entry description
     * @return Posted journal entry
     */
    JournalEntry postPaymentApplication(
            UUID paymentApplicationId, UUID cashAccountId, UUID arAccountId, BigDecimal amount, String description);
}
