package com.positivity.image.internal.exception;

/**
 * A genuine client input-validation failure in this module — a store request whose {@code
 * content} is not valid base64, or that decodes to an empty body. Services and controllers must
 * throw this (never bare {@code IllegalArgumentException}) for that case: {@link
 * ImageExceptionHandler} maps it to {@code 400 IMAGE_REQUEST_INVALID}, echoing the message.
 *
 * <p>Bare {@code IllegalArgumentException} must not be used for input validation here because it
 * is not exclusive to this module's own throws — it is also what Hibernate/JPA throw for an
 * invalid query, what {@code UUID.fromString} throws on malformed stored data, and what a JPA
 * attribute converter throws on corrupt stored JSON (issue #1694). A blanket handler that catches
 * {@code IllegalArgumentException} would report any of those server-side defects back to the
 * client as a 400, leaking internal class names and query text into the response body. A type
 * this module controls, thrown only where the module itself validates its own input, cannot be
 * confused with an unrelated persistence or parsing failure: anything else typed {@code
 * IllegalArgumentException} now falls through to the platform's generic 500 handler ({@code
 * pos-web-common}'s {@code GlobalApiExceptionHandler}).
 */
public class ImageValidationException extends RuntimeException {

    public ImageValidationException(String message) {
        super(message);
    }

    public ImageValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
