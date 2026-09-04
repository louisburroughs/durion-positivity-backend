package com.positivity.invoice.internal.exception;

/**
 * A finalize/revert request is shape-valid but the invoice-finalization permission matrix
 * ({@code InvoiceFinalizationServiceImpl}) requires a manager-approval elevation token that was
 * not supplied. The elevation token is a step-up credential, so its absence is a refusal about
 * what the caller is allowed to do, not about the request (ADR-0017 §2 question 1, decided in
 * #1725): maps to HTTP 403 with code {@code MANAGER_APPROVAL_REQUIRED} and a {@code nextAction}
 * pointing at {@code elevateManagerApproval}. Introduced in #1694 as a 422.
 */
public class ManagerApprovalRequiredException extends RuntimeException {

    public ManagerApprovalRequiredException(String message) {
        super(message);
    }
}
