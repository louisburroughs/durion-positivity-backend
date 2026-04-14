package com.positivity.accounting.internal.enums;

/**
 * GL Account types following standard accounting classification.
 *
 * @see <a href=
 *      "domains/accounting/.business-rules/BACKEND_CONTRACT_GUIDE.md">Backend
 *      Contract Guide</a>
 */
public enum AccountType {
    /**
     * Assets - resources owned by the company with economic value.
     * Examples: Cash, Accounts Receivable, Inventory, Fixed Assets
     */
    ASSET,

    /**
     * Liabilities - obligations owed to external parties.
     * Examples: Accounts Payable, Loans, Accrued Expenses
     */
    LIABILITY,

    /**
     * Equity - owner's residual interest in assets after liabilities.
     * Examples: Common Stock, Retained Earnings, Additional Paid-in Capital
     */
    EQUITY,

    /**
     * Revenue - income from business operations.
     * Examples: Sales Revenue, Service Revenue, Interest Income
     */
    REVENUE,

    /**
     * Expense - costs incurred in generating revenue.
     * Examples: Cost of Goods Sold, Salaries, Rent, Utilities
     */
    EXPENSE
}
