package com.positivity.accounting.internal.exception;

/**
 * Thrown when an inbound vendor invoice-received event cannot be matched to
 * any pending receipt/bill for the vendor. The request (event) is
 * structurally valid; no candidate satisfies the domain matching policy —
 * ADR-0017 §2 would otherwise favor 422 for this shape of failure, but this
 * type maps to HTTP 400 (NO_MATCHING_VENDOR_BILL) to match the existing,
 * explicitly documented contract on {@code POST /v1/accounting/ap/bills/match-invoice}.
 */
public class VendorBillMatchNotFoundException extends RuntimeException {

    public VendorBillMatchNotFoundException(String message) {
        super(message);
    }
}
