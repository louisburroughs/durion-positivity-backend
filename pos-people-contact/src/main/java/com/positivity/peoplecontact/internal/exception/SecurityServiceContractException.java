package com.positivity.peoplecontact.internal.exception;

/**
 * A downstream {@code pos-security-service} rejection that no caller-supplied value could have
 * caused — evidence of a request-shape/contract drift between this client and
 * pos-security-service (for example {@code GET /v1/users} starting to require the username
 * filter this client does not send today, or its response ceasing to be a plain user list), not
 * a bad request from whoever called into this module.
 *
 * <p>The drift this type exists to catch is real and has happened: ADR-0061 deleted the
 * {@code scopeType} scope from role assignments (issue #1875) while this client kept sending
 * it, and because Spring drops unknown query parameters and Jackson ignores unknown body
 * fields, that drift produced no 400 at all — it degraded silently instead.
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
