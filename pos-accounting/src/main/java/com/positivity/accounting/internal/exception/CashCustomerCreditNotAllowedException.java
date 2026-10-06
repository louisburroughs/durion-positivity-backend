package com.positivity.accounting.internal.exception;

/**
 * Thrown when a person's command would keep money of the CASH walk-in house account as a customer
 * credit (CAP:550 S11, #2508; SPEC-accounting-workspace §4.4 item 4, AW12): an application with an
 * overpayment, or crediting a payment's remainder. A credit on the CASH account would be a liability
 * to no identifiable person, so the excess is refunded through pos-invoice's payment refund instead.
 * Nothing is written. Maps to 422 CASH_CUSTOMER_CREDIT_NOT_ALLOWED (ADR-0017 §2: the refusal is about
 * the referenced payment's customer).
 */
public class CashCustomerCreditNotAllowedException extends RuntimeException {

    /** The message every refusal carries: what happens to a walk-in overpayment instead. */
    public static final String MESSAGE = "Walk-in overpayments are refunded, not kept as credit";

    public CashCustomerCreditNotAllowedException(String detail) {
        super(MESSAGE + ": " + detail);
    }
}
