package com.positivity.mcp.internal.exception;

/**
 * An LLM API configuration create/update was rejected because another configuration already uses
 * the requested {@code apiId}. A stateful collision against existing data, not a malformed
 * request. {@code LlmApiConfigController} has no dedicated {@code @RestControllerAdvice} (issue
 * #1694 tracked only {@code SystemPromptExceptionHandler} and {@code NltiExceptionHandler}), so
 * this still surfaces as a generic 500 via {@code GlobalApiExceptionHandler}'s catch-all -- the
 * controller's own Javadoc already documents that gap ("this module surfaces that failure as a
 * 500 rather than a 409"). Retyped off {@code IllegalArgumentException} for the same reason as the
 * rest of this sweep even though its HTTP mapping is unchanged: see the module report for #1694.
 */
public class LlmApiIdAlreadyExistsException extends RuntimeException {
    public LlmApiIdAlreadyExistsException(String message) {
        super(message);
    }
}
