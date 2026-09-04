package com.positivity.accounting.internal.exception;

/**
 * Thrown when an AP payment's requested bill allocation cannot be honored:
 * an allocated bill does not exist, is not APPROVED, does not belong to the
 * paying vendor, or the allocation total exceeds the payment's gross amount.
 * Maps to HTTP 400 (VALIDATION_ERROR) — documented on
 * {@code POST /v1/accounting/ap/payments} (AP_PAYMENT_EXECUTE): "400 when a
 * bill is missing, unapproved or over-allocated."
 *
 * <p>Deliberately does NOT extend {@link IllegalArgumentException}:
 * {@link com.positivity.accounting.internal.service.APPaymentServiceImpl#executePayment}
 * catches this type specifically (after the gateway call has already
 * succeeded) to avoid marking the payment GATEWAY_FAILED for what is really
 * a post-authorization allocation validation failure, not a gateway error.
 */
public class InvalidBillAllocationException extends RuntimeException {

    public InvalidBillAllocationException(String message) {
        super(message);
    }
}
