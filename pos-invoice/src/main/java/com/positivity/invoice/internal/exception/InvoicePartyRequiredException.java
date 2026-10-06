package com.positivity.invoice.internal.exception;

/**
 * The invoice has no bill-to party, so it cannot be finalised and no payment can be initiated or
 * captured against it (CAP:550 S9, spec §4.4 item 1, AW12). This is the backstop behind the
 * checkout customer requirement (S8): it closes gap G6, where a party-less counter sale posted to
 * accounts receivable while pos-accounting skipped its settlement. A shape-valid request refused
 * by domain policy — HTTP 422 {@code INVOICE_PARTY_REQUIRED} per ADR-0017 §1/§2.
 */
public class InvoicePartyRequiredException extends RuntimeException {

    public static final String CODE = "INVOICE_PARTY_REQUIRED";

    public InvoicePartyRequiredException(String message) {
        super(message);
    }
}
