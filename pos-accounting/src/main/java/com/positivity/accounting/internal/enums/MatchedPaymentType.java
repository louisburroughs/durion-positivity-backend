package com.positivity.accounting.internal.enums;

/**
 * Which platform payment ledger a settlement line matched to (story F1c, issue #963): accounts
 * receivable ({@code ReceivablePayment}, CHARGE lines) or accounts payable ({@code APPayment},
 * REFUND/payout lines) — decision D-11.
 */
public enum MatchedPaymentType {
    AR,
    AP
}
