package com.positivity.invoice.internal.exception;

/**
 * A finalize/revert request is shape-valid but the invoice-finalization permission matrix
 * ({@code InvoiceFinalizationServiceImpl}) requires a manager-approval elevation token that was
 * not supplied — a documented domain-policy condition on the caller's role and the invoice's
 * amount, not a plain missing-field error (issue #1694). Maps to HTTP 422 per ADR-0017 §2
 * ("payload shape is valid ... but the requested operation violates domain policy rules that are
 * explicitly documented in the endpoint contract").
 */
public class ManagerApprovalRequiredException extends RuntimeException {

    public ManagerApprovalRequiredException(String message) {
        super(message);
    }
}
