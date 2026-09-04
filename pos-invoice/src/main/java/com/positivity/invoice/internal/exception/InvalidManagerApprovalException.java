package com.positivity.invoice.internal.exception;

/**
 * A manager-approval elevation token was supplied for a finalize/revert request but does not
 * verify (wrong scope, tampered, or expired). A step-up credential the server considers
 * insufficient is a refusal about the caller's authorization (ADR-0017 §2 question 1, decided
 * in #1725): maps to HTTP 403 with code {@code MANAGER_APPROVAL_INVALID}, mirroring {@link
 * ManagerApprovalRequiredException}. Introduced in #1694 as a 422.
 */
public class InvalidManagerApprovalException extends RuntimeException {

    public InvalidManagerApprovalException(String message) {
        super(message);
    }
}
