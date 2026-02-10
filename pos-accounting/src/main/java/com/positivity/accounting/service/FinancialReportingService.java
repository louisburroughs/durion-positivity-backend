package com.positivity.accounting.service;

import com.positivity.accounting.internal.dto.*;
import org.jspecify.annotations.NonNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Service for generating financial statements and drilldown reports.
 * 
 * Produces Income Statement and Balance Sheet from posted GL entries,
 * with drilldown capability to source transactions.
 * 
 * All reports use POSTED journal entries only (excludes DRAFT).
 * Reports are reproducible: same parameters always yield same results.
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
    IncomeStatementReport generateIncomeStatement(
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate);

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
            @NonNull String statementLineCode,
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate);

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
            @NonNull String accountId,
            @NonNull LocalDate startDate,
            @NonNull LocalDate endDate);
}
