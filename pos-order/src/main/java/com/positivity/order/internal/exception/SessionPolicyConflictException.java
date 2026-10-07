package com.positivity.order.internal.exception;

/**
 * A drawer-policy PUT that lost a race with another PUT (CAP:550 S16, #2512): 409 {@code
 * SESSION_POLICY_CONFLICT}; the caller reads the policy again and retries.
 */
public class SessionPolicyConflictException extends RuntimeException {

    public SessionPolicyConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
