package com.positivity.accounting.internal.enums;

/**
 * An item still in transit when a bank account's books open (#2572, OI-10). Each posts as its own bank line, so
 * bank reconciliation can register it as an outstanding item and match it when it clears.
 */
public enum BankOpeningItemType {
    /** A check written before cutover the bank has not yet paid: a credit to the bank account. */
    OUTSTANDING_CHECK,

    /** A deposit made before cutover the bank has not yet credited: a debit to the bank account. */
    DEPOSIT_IN_TRANSIT
}
