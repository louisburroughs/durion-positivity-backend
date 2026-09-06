package com.positivity.securityservice.internal.exception;

import org.jspecify.annotations.Nullable;

/**
 * A referenced user does not resolve — by id, or by username.
 *
 * <p>Mapped to {@code 404 Not Found} with code {@code USER_NOT_FOUND} by
 * {@code GlobalExceptionHandler}, on every entry point that can reach it (ADR-0017 §1 and §2:
 * one domain condition, one status, encoded here on the exception class rather than at the throw
 * site so two callers cannot answer differently; #1802). {@code 400} is reserved for request
 * shape and is never the answer for a reference that does not resolve.
 *
 * <p>Two constructions exist because two disclosure postures exist:
 *
 * <ul>
 *   <li>{@link #UserNotFoundException(String)} echoes its message. Use it where the caller may
 *       learn which user was missing — a user-management endpoint keyed by a UUID the caller
 *       supplied, for example.
 *   <li>{@link #withLogDetail(String, String)} carries a deliberately generic client-facing
 *       message and a separate diagnostic detail that {@code GlobalExceptionHandler} logs
 *       against the response's correlation id and never puts in the {@code ApiError} body. Use
 *       it where the reason a subject failed to resolve is itself information the caller must
 *       not receive — the token-issuance endpoints, where echoing the subject let a caller
 *       enumerate which accounts exist (#1715; ADR-0056 §1: rejected values are never echoed).
 * </ul>
 */
public class UserNotFoundException extends RuntimeException {

    private final @Nullable String logDetail;

    public UserNotFoundException(String message) {
        super(message);
        this.logDetail = null;
    }

    public UserNotFoundException(String message, Throwable cause) {
        super(message, cause);
        this.logDetail = null;
    }

    /**
     * Creates a not-found failure whose client-facing {@code message} is deliberately generic
     * and whose diagnostic detail travels separately, so the handler can log the detail against
     * the response's correlation id without echoing it into the body. Mirrors
     * {@link SecurityValidationException#withLogDetail(String, String)}, and is a static factory
     * for the same reason: a {@code (String, String)} constructor next to
     * {@code (String, Throwable)} reads identically at a call site and silently drops a cause.
     *
     * @param message   the generic, client-safe message placed in the {@code ApiError} body
     * @param logDetail the sensitive detail; logged with the correlation id, never returned
     * @return the exception to throw
     */
    public static UserNotFoundException withLogDetail(String message, @Nullable String logDetail) {
        return new UserNotFoundException(message, logDetail, null);
    }

    private UserNotFoundException(String message, @Nullable String logDetail, @Nullable Void disambiguator) {
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
