package com.positivity.accounting.internal.enums;

/**
 * Optional GL account subtype refining {@link AccountType}.
 *
 * A pragmatic subset of Odoo's {@code account_type} taxonomy. Drives report
 * grouping and posting-config plausibility validation (e.g. cash-receipt /
 * settlement mappings should resolve to BANK_CASH, UNDEPOSITED_FUNDS, or
 * RECEIVABLE accounts). Nullable on {@code GLAccount} — existing accounts
 * without a subtype are unaffected.
 *
 * @see <a href=
 *      "domains/accounting/plan-odoo-parity-pos-accounting.md">Odoo parity plan
 *      - Story H1</a>
 */
public enum AccountSubtype {
    /** Accounts receivable (ASSET). Example: 1200 Accounts Receivable. */
    RECEIVABLE,

    /** Accounts payable (LIABILITY). Example: 2000 Accounts Payable. */
    PAYABLE,

    /** Bank and cash accounts (ASSET). Example: 1000 Cash. */
    BANK_CASH,

    /**
     * Undeposited funds clearing account (ASSET). Cash receipts post here
     * until settlement reconciliation clears them to a bank/cash account.
     */
    UNDEPOSITED_FUNDS,

    /** Sales tax payable (LIABILITY). Example: 2200 Sales Tax Payable. */
    TAX_PAYABLE,

    /** Other current assets (ASSET). Examples: Inventory, Prepaid Expenses. */
    CURRENT_ASSET,

    /** Fixed assets (ASSET). Examples: Equipment, Vehicles. */
    FIXED_ASSET,

    /**
     * Other current liabilities (LIABILITY). Example: 2300 Customer Credit
     * Liability.
     */
    CURRENT_LIABILITY,

    /** Sales / operating revenue (REVENUE). Example: 4000 Service Revenue. */
    SALES,

    /** Cost of sales (EXPENSE). Example: 5000 Cost of Goods Sold. */
    COST_OF_SALES,

    /** Operating expenses (EXPENSE). Example: 6000 Payment Processor Fees. */
    OPERATING_EXPENSE,

    /** Anything not covered by a more specific subtype. */
    OTHER,

    /**
     * Cash kept on the premises, outside the bank (ASSET). Example: 1080 Register Float, the drawers'
     * change float (#2511, AW9, AW16). Never {@link #BANK_CASH}: bank reconciliation does not see it.
     */
    CASH_ON_HAND,

    /**
     * Indirect tax the shop paid and may recover from the tax authority (ASSET), one account per regime (CAP:550 S32d,
     * AW20). The accounts reach a tenant only through its currency's template data; postings resolve them through the
     * {@code INPUT_TAX_<regime>} and {@code TAX_RECOVERABLE_<regime>} mapping keys, never by number.
     */
    TAX_RECOVERABLE
}
