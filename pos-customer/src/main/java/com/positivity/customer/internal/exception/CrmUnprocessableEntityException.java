package com.positivity.customer.internal.exception;

/**
 * A syntactically valid request the domain cannot process — e.g. a segment predicate
 * that parses but references an attribute outside the whitelisted catalog. Maps to
 * {@code 422} so callers can tell it apart from a malformed body ({@code 400}).
 */
public class CrmUnprocessableEntityException extends RuntimeException {

    public CrmUnprocessableEntityException(String message) {
        super(message);
    }
}
