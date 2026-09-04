package com.positivity.peoplecontact.internal.exception;

/**
 * A downstream {@code pos-security-service} rejection that no caller-supplied value could have
 * caused — evidence of a request-shape/contract drift between this client and
 * pos-security-service (for example a renamed {@code scopeType} enum, or {@code GET /v1/users}
 * starting to require a parameter it does not send today), not a bad request from whoever
 * called into this module.
 *
 * <p>Deliberately NOT mapped by {@code PeopleExceptionHandler}: unlike {@link
 * PeopleContactValidationException} — reserved for failures a caller's own input could actually
 * cause — this type must fall through to {@code pos-web-common}'s platform-wide {@code
 * GlobalApiExceptionHandler}, which answers a generic, correlated {@code 500 INTERNAL_ERROR} and
 * logs this exception (message plus stack trace) at ERROR against that correlation id. The
 * downstream detail carried in {@link #getMessage()} only ever reaches that log — the response
 * body never echoes it — because attributing a server-side contract defect to the client (the
 * exact failure this type exists to prevent) would mislead every caller into "fixing" input
 * that was never the problem.
 */
public class SecurityServiceContractException extends RuntimeException {

    public SecurityServiceContractException(String message) {
        super(message);
    }
}
