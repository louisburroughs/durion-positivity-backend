package com.positivity.invoice.internal.exception;

/**
 * A manager-approval elevation token was supplied for a finalize/revert request but does not
 * verify (wrong scope, tampered, or expired) — a documented domain-policy condition, not a
 * malformed request (issue #1694). Maps to HTTP 422 per ADR-0017 §2, mirroring {@link
 * ManagerApprovalRequiredException}.
 */
public class InvalidManagerApprovalException extends RuntimeException {

    public InvalidManagerApprovalException(String message) {
        super(message);
    }
}
