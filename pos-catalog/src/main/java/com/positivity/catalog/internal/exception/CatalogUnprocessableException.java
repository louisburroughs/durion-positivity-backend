package com.positivity.catalog.internal.exception;

/**
 * A well-formed request whose content the domain cannot accept — a reference to a vocabulary
 * value that does not exist, a range outside what the referenced thing covers. Answered 422 with
 * a stable {@code code} so a caller can act on it, distinct from the 400 of malformed input and
 * the 409 of a business-rule conflict (ADR-0017; AGENT_GUIDE §446).
 */
public class CatalogUnprocessableException extends RuntimeException {

    private final String code;

    public CatalogUnprocessableException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
