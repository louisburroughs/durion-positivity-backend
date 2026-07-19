package com.positivity.accounting.internal.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Internal aggregation projection for the Trial Balance report (story G1,
 * issue #956): per-GL-account debit/credit totals summed from POSTED journal
 * lines up to the as-of date. Produced by a JPQL constructor expression in
 * {@code JournalEntryRepository#sumPostedDebitsCreditsByAccountAsOf}; not part
 * of the API surface (the response row is {@link TrialBalanceRow}).
 *
 * @param glAccountId GL account ID
 * @param accountCode chart-of-accounts code (account number)
 * @param accountName GL account display name
 * @param totalDebit  sum of POSTED debit amounts, never null (COALESCE 0)
 * @param totalCredit sum of POSTED credit amounts, never null (COALESCE 0)
 */
public record TrialBalanceAccountTotal(
        UUID glAccountId, String accountCode, String accountName, BigDecimal totalDebit, BigDecimal totalCredit) {}
