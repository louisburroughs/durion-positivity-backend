package com.positivity.mcp.internal.exception;

/**
 * An LLM API configuration create/update was rejected because another configuration already uses
 * the requested {@code apiId}. A stateful collision against existing data, not a malformed
 * request, so ADR-0017 §2 makes it a 409.
 *
 * <p>Retyped off {@code IllegalArgumentException} in #1694. It then had no advice to land in and
 * surfaced as a generic 500 via {@code GlobalApiExceptionHandler}'s catch-all; {@code
 * LlmApiConfigExceptionHandler} maps it to its documented 409 as of #1713.
 */
public class LlmApiIdAlreadyExistsException extends RuntimeException {
    public LlmApiIdAlreadyExistsException(String message) {
        super(message);
    }
}
