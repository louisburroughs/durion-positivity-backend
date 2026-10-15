package com.positivity.securityservice.internal.exception;

import java.util.List;

/**
 * The URL offered for the tenant's audit export webhook breaks a destination rule (#2497): not an
 * absolute {@code https} URL with a DNS host, user info or a fragment present, too long, or a host
 * that does not resolve or resolves to an address that is not publicly routable.
 *
 * <p>{@code GlobalExceptionHandler} answers {@code 400 VALIDATION_ERROR} (ADR-0017 §1) with one
 * {@code fieldErrors} entry on {@code url} per problem.
 */
public class AuditExportWebhookInvalidException extends RuntimeException {

    /** The request field every field error is reported on. */
    public static final String FIELD = "url";

    private final transient List<String> fieldMessages;

    /**
     * @param fieldMessages one message per problem, reported on {@link #FIELD}
     */
    public AuditExportWebhookInvalidException(List<String> fieldMessages) {
        super("The audit export webhook URL is not an allowed destination");
        this.fieldMessages = List.copyOf(fieldMessages);
    }

    /**
     * @return one message per problem, reported on {@link #FIELD}
     */
    public List<String> fieldMessages() {
        return fieldMessages;
    }
}
