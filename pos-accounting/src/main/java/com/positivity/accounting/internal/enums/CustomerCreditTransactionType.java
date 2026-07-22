package com.positivity.accounting.internal.enums;

/**
 * The two ways a customer credit is drawn down (issue #992). Each relieves the Customer
 * Credit Liability (2300) recognized at issuance; they differ only in the contra account.
 */
public enum CustomerCreditTransactionType {

    /** Applied to an invoice: {@code Dr Customer Credit Liability / Cr Accounts Receivable}. */
    APPLICATION,

    /** Refunded to the customer: {@code Dr Customer Credit Liability / Cr Undeposited Funds}. */
    REFUND
}
