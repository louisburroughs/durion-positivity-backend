package com.positivity.securityservice.internal.exception;

import java.util.List;

/**
 * The permission-holders read was asked about a well-formed code that is not in the permission
 * catalog (#2669).
 *
 * <p>Refused rather than answered with no holders: an empty answer for a typo would read as
 * "nobody holds this", a false statement about separation of duties. {@code GlobalExceptionHandler}
 * answers {@code 422 PERMISSION_NOT_REGISTERED} (ADR-0017 §2: a referenced resource in a query
 * parameter that does not exist is a domain refusal, not a 404 of the target) with one
 * {@code fieldErrors} entry on {@code permission} per unregistered code.
 */
public class PermissionNotRegisteredException extends RuntimeException {

    private final transient List<String> unregistered;

    /**
     * @param unregistered the requested codes the catalog does not hold, in request order
     */
    public PermissionNotRegisteredException(List<String> unregistered) {
        super("Not a registered permission code: " + String.join(", ", unregistered));
        this.unregistered = List.copyOf(unregistered);
    }

    /**
     * @return the requested codes the catalog does not hold, in request order
     */
    public List<String> unregistered() {
        return unregistered;
    }
}
