package com.positivity.securityservice.internal.exception;

import org.jspecify.annotations.Nullable;

/**
 * A genuine client input-validation failure in this module — a blank required field, a
 * malformed permission key or scope/location combination, a role name that does not resolve, a
 * malformed Base64URL bitset, an unsupported {@code perm_ver}. A <em>user</em> reference that
 * does not resolve is not this: it is {@link UserNotFoundException} (404) on every entry point
 * (#1802). Services and controllers must throw this (never bare
 * {@code IllegalArgumentException}) for that case:
 * {@code GlobalExceptionHandler} maps it to {@code 400 VALIDATION_ERROR} (ADR-0017 §1), echoing
 * the message — or, when the two-argument {@code (message, logDetail)} form is used, echoing only
 * the generic message and logging the detail against the correlation id. {@code VALIDATION_ERROR}
 * is the same code the other modules that introduced a fresh generic validation type in #1694
 * converged on — pos-people, pos-people-contact, pos-warranty,
 * pos-accounting and pos-customer. It answered {@code INVALID_REQUEST} until #1730; the type was
 * new, so there was no wire contract to preserve.
 *
 * <p>Bare {@code IllegalArgumentException} must not be used for input validation here because it
 * is not exclusive to this module's own throws — it is also what Hibernate/JPA throw for an
 * invalid query and what {@code UUID.fromString} throws on malformed stored data. In an auth
 * service, a blanket {@code @ExceptionHandler(IllegalArgumentException.class)} reported such
 * server-side defects back to the client as a {@code 400} validation error, leaking internal
 * class names and query text (issue #1694). A type this module controls, thrown only where the
 * module itself validates its own input, cannot be confused with an unrelated persistence or
 * parsing failure: anything else typed {@code IllegalArgumentException} now falls through to the
 * platform's generic 500 handler ({@code pos-web-common}'s {@code GlobalApiExceptionHandler}).
 */
public class SecurityValidationException extends RuntimeException {

    private final @Nullable String logDetail;

    public SecurityValidationException(String message) {
        super(message);
        this.logDetail = null;
    }

    public SecurityValidationException(String message, Throwable cause) {
        super(message, cause);
        this.logDetail = null;
    }

    /**
     * Creates a validation failure whose client-facing {@code message} is deliberately generic
     * and whose diagnostic detail is carried separately, so {@code GlobalExceptionHandler} can
     * log the detail against the response's correlation id without echoing it into the
     * {@code ApiError} body.
     *
     * <p>Use this whenever the reason a value failed to resolve is itself information the caller
     * should not receive — the motivating case is a token-issuance subject that does not exist,
     * where echoing "User not found for subject: X" let a caller enumerate which accounts exist
     * (issue #1715). ADR-0056 §1 states the same rule for the platform advice: rejected data
     * values are never echoed, and the correlation id is the diagnostic handle.
     *
     * <p>Exposed as a static factory rather than a {@code (String, String)} constructor: that
     * signature sits next to {@code (String, Throwable)} and reads identically at a call site
     * written as {@code new SecurityValidationException(msg, e.getMessage())}, which would silently
     * drop the cause. The name makes the intent unmistakable.
     *
     * @param message   the generic, client-safe message placed in the {@code ApiError} body
     * @param logDetail the sensitive detail; logged with the correlation id, never returned
     * @return the exception to throw
     */
    public static SecurityValidationException withLogDetail(String message, @Nullable String logDetail) {
        return new SecurityValidationException(message, logDetail, null);
    }

    private SecurityValidationException(String message, @Nullable String logDetail, @Nullable Void disambiguator) {
        super(message);
        this.logDetail = logDetail;
    }

    /**
     * @return the sensitive diagnostic detail for logging, or {@code null} when there is none
     */
    public @Nullable String getLogDetail() {
        return logDetail;
    }
}
