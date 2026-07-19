package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.*;
import java.time.LocalDate;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

/**
 * Service for generating financial statements and drilldown reports.
 *
 * Produces Income Statement and Balance Sheet from posted GL entries,
 * with drilldown capability to source transactions.
 *
 * All reports use POSTED journal entries only (excludes DRAFT).
 * Financial content (amounts, balances, line items) is reproducible for the
 * same parameters and underlying posted data, while non-financial metadata
 * (such as generation timestamps) may vary between calls.
 *
 * @see <a href="durion-positivity-backend#125">Backend Story #125</a>
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Contract
 *      Guide</a>
 */
public interface FinancialReportingService {

    /**
     * Generate Income Statement (Profit & Loss) for specified date range.
     *
     * Aggregates all POSTED journal lines within the period, grouped by
     * statement line mappings.
     *
     * @param startDate period start date (inclusive)
     * @param endDate   period end date (inclusive)
     * @return income statement report with revenue, expenses, and net income
     * @throws IllegalArgumentException if endDate < startDate
     */
    @NonNull
    IncomeStatementReport generateIncomeStatement(@NonNull LocalDate startDate, @NonNull LocalDate endDate);

    /**
     * Generate Balance Sheet as of specified date.
     *
     * Aggregates all POSTED journal lines up to and including the specified date,
     * grouped by statement line mappings.
     *
     * Validates: Assets = Liabilities + Equity (within tolerance).
     *
     * @param asOfDate reporting date
     * @return balance sheet report with assets, liabilities, equity, and balance
     *         flag
     */
    @NonNull
    BalanceSheetReport generateBalanceSheet(@NonNull LocalDate asOfDate);

    /**
     * Generate Trial Balance as of specified date.
     *
     * Aggregates all POSTED journal lines up to and including the as-of date
     * into per-account debit/credit/balance rows (ordered by account number),
     * with grand totals proving the balance constraint (sum of debits equals
     * sum of credits; the {@code balanced} flag is false when the constraint
     * is violated).
     *
     * Also runs the entry-number gap-check (per monthly sequence scope, see
     * {@code AccountingSequenceRepository#findMissingEntryNumbers}) and
     * attaches the results as a footnote block; the footnote is empty on a
     * clean ledger.
     *
     * Returns empty rows with zero totals (balanced = true) and an empty gap
     * footnote when no POSTED data exists as of the requested date.
     *
     * @param asOf reporting date (inclusive)
     * @return trial balance report with per-account rows, grand totals, and
     *         entry-number gap footnote
     */
    @NonNull
    TrialBalanceReport generateTrialBalance(@NonNull LocalDate asOf);

    /**
     * Drill down from a statement line to see contributing GL accounts.
     *
     * Returns all accounts mapped to the specified statement line code,
     * with their period balances.
     *
     * Example: "PL_EXPENSE_OPERATING" → [Salaries: $50k, Rent: $20k, Utilities:
     * $5k]
     *
     * @param statementLineCode statement line code (e.g., "PL_REVENUE_SALES")
     * @param startDate         period start date
     * @param endDate           period end date
     * @return list of accounts with balances
     */
    @NonNull
    List<AccountDrilldownResponse> drilldownToAccounts(
            @NonNull String statementLineCode, @NonNull LocalDate startDate, @NonNull LocalDate endDate);

    /**
     * Drill down from a GL account to see individual journal lines.
     *
     * Returns all POSTED journal lines for the specified account within
     * the period, with transaction details and source event references.
     *
     * @param accountId GL account ID
     * @param startDate period start date
     * @param endDate   period end date
     * @return list of journal lines with debits/credits
     */
    @NonNull
    List<JournalLineDrilldownResponse> drilldownToJournalLines(
            @NonNull String accountId, @NonNull LocalDate startDate, @NonNull LocalDate endDate);

    /**
     * Generate a General Ledger report for a date range (Story G2, Issue #960).
     *
     * <p>
     * Produces per-account chronological POSTED journal lines with a running
     * balance, one {@link GeneralLedgerAccountSection} per account. When
     * {@code accountId} is provided, the report is scoped to that single account;
     * when {@code null}, all accounts with activity are included, ordered by
     * account number.
     *
     * <p>
     * <b>Line selection and status semantics (must match the trial-balance /
     * drilldown queries — Story C2 note).</b> Only journal lines whose parent
     * entry status is {@code POSTED} are included. An A3-REVERSED original entry
     * (status {@code REVERSED}) is <em>excluded</em>, while its POSTED reversing
     * entry <em>is</em> included; the reversing pair therefore nets to zero in
     * both the running balance and the section totals. The domain slice MUST NOT
     * special-case reversal linkage — filtering strictly on {@code POSTED} status
     * yields the correct net-zero behavior for reversed/reversing pairs.
     *
     * <p>
     * <b>Balances.</b> {@code openingBalance} is the signed net (debit positive)
     * of POSTED activity strictly before {@code startDate}. Each line's
     * {@code runningBalance} is the opening balance plus the cumulative signed net
     * of in-period lines up to and including that line, ordered by transaction
     * date then entry number. {@code closingBalance} equals opening balance plus
     * in-period net activity. Grand totals sum in-period debit/credit across
     * sections.
     *
     * <p>
     * Returns empty {@code accounts} with zero grand totals when no POSTED
     * activity exists in the range for the filter.
     *
     * @param accountId optional GL account UUID filter; {@code null} spans all
     *                  accounts
     * @param startDate period start date (inclusive)
     * @param endDate   period end date (inclusive)
     * @return general ledger report with per-account sections and running balances
     * @throws IllegalArgumentException if {@code endDate} is before
     *                                  {@code startDate}
     */
    @NonNull
    GeneralLedgerReport generateGeneralLedger(
            @Nullable String accountId, @NonNull LocalDate startDate, @NonNull LocalDate endDate);

    /**
     * Generate the Aged Receivables (aged AR) report as of a date (Story G2,
     * Issue #960).
     *
     * <p>
     * Buckets each customer's open (unpaid) invoice balances by days past due.
     * Open balance per invoice is the invoice total minus applied payment
     * allocations (the same net-open computation used by
     * {@code InvoiceBalanceCalculator}); only invoices with a positive open
     * balance as of {@code asOfDate} contribute.
     *
     * <p>
     * <b>Bucket math.</b> Let {@code d = asOfDate - dueDate} in whole days, using
     * the invoice due date (fall back to the invoice date when no due date is
     * set). Bucket assignment:
     * <ul>
     * <li>{@code current} (0-30): {@code d <= 30} (includes not-yet-due, i.e.
     * {@code d <= 0})</li>
     * <li>{@code days31To60}: {@code 31 <= d <= 60}</li>
     * <li>{@code days61To90}: {@code 61 <= d <= 90}</li>
     * <li>{@code days90Plus}: {@code d >= 91}</li>
     * </ul>
     * Each invoice's full open balance lands in exactly one bucket per its own age
     * (invoice-level aging). {@code totalOutstanding} per row is the sum of that
     * customer's buckets; {@code totals} is the grand-total buckets across rows.
     *
     * <p>
     * Returns empty rows and all-zero {@code totals} when no open receivables
     * exist as of the date.
     *
     * @param asOfDate reporting date (inclusive) used to compute days past due
     * @return aged receivables report with per-customer bucketed balances
     */
    @NonNull
    AgedReceivablesReport generateAgedReceivables(@NonNull LocalDate asOfDate);

    /**
     * Generate the Aged Payables (aged AP) report as of a date (Story G2,
     * Issue #960).
     *
     * <p>
     * Mirror of {@link #generateAgedReceivables(LocalDate)} for the payables side.
     * Buckets each vendor's open (unpaid) vendor-bill balances by days past due.
     * Open balance per bill is the bill total minus applied
     * {@code APPaymentAllocation} amounts; only bills with a positive open balance
     * as of {@code asOfDate} contribute.
     *
     * <p>
     * Bucket math is identical to aged AR: {@code d = asOfDate - dueDate} (fall
     * back to bill date when no due date), with edges 0-30 / 31-60 / 61-90 / 90+
     * as documented on {@link #generateAgedReceivables(LocalDate)}. Bill-level
     * aging: each bill's full open balance lands in one bucket.
     * {@code totalOutstanding} per row is the vendor's bucket sum; {@code totals}
     * is the grand-total buckets across rows.
     *
     * <p>
     * Returns empty rows and all-zero {@code totals} when no open payables exist
     * as of the date.
     *
     * @param asOfDate reporting date (inclusive) used to compute days past due
     * @return aged payables report with per-vendor bucketed balances
     */
    @NonNull
    AgedPayablesReport generateAgedPayables(@NonNull LocalDate asOfDate);
}
