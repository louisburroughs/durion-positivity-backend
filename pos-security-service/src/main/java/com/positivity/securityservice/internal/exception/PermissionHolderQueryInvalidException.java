package com.positivity.securityservice.internal.exception;

import java.util.List;

/**
 * The {@code permission} query of the permission-holders read is malformed (#2669): no code, more
 * than the maximum number of distinct codes, or a code that is not {@code domain:resource:action}.
 *
 * <p>{@code GlobalExceptionHandler} answers {@code 400 VALIDATION_ERROR} (ADR-0017 §1) with one
 * {@code fieldErrors} entry on {@code permission} per problem, each naming the bad value.
 */
public class PermissionHolderQueryInvalidException extends RuntimeException {

    /** The query parameter every field error is reported on. */
    public static final String FIELD = "permission";

    private final transient List<String> fieldMessages;

    /**
     * @param fieldMessages one message per problem, reported on {@link #FIELD}
     */
    public PermissionHolderQueryInvalidException(List<String> fieldMessages) {
        super("Invalid permission query");
        this.fieldMessages = List.copyOf(fieldMessages);
    }

    /**
     * @return one message per problem, reported on {@link #FIELD}
     */
    public List<String> fieldMessages() {
        return fieldMessages;
    }
}
