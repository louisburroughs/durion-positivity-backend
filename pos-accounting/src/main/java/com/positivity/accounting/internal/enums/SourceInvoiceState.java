package com.positivity.accounting.internal.enums;

/**
 * The state of the invoice a customer payment was taken against, as accounting's own records show it
 * (#2563). One rule, {@code SourceInvoiceRule}, decides it for both readers: automatic application of
 * a settled payment (CAP:550 S2, #2503, cases b, c, d and g) and the unapplied-payments list (S1,
 * #2502), so the list can never say something the automatic decision would not.
 */
public enum SourceInvoiceState {

    /** AR-eligible, the payment's customer's, with an open balance the payment can pay. */
    OPEN,

    /**
     * AR-eligible, the payment's customer's, with no open balance left (case g). It may have been paid by
     * this very payment, so it is a possible duplicate only when the payment applied nothing to it.
     */
    PAID,

    /** Not in the {@code ext_invoice} replica yet (case b, {@code SUSPENDED / INVOICE_NOT_FOUND}). */
    NOT_FOUND,

    /**
     * Replicated but not {@code FINALIZED} or {@code POSTED}, so it cannot take a payment (case c, {@code
     * FAILED / INVOICE_NOT_ELIGIBLE}).
     */
    NOT_ELIGIBLE,

    /**
     * Its party, read as a UUID, is missing or is not the payment's customer (case d, BR-2, {@code SKIPPED /
     * NOT_POSTABLE}).
     */
    OTHER_CUSTOMER
}
