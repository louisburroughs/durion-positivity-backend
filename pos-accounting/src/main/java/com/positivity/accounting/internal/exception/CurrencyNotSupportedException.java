package com.positivity.accounting.internal.exception;

/**
 * Thrown when a request would book a document in a currency the ledger does not book (ADR-0067
 * PC-9 (a)): a Stage A ledger books its own currency only, so a payment in another currency is not
 * applied to invoices (issue #2310, corrected by #2334). The request is refused before any amount
 * moves. The refusal is about the state of a referenced resource, so it maps to 422
 * CURRENCY_NOT_SUPPORTED (ADR-0017 §2), the same platform code bank reconciliation answers.
 */
public class CurrencyNotSupportedException extends RuntimeException {

    public CurrencyNotSupportedException(String message) {
        super(message);
    }
}
