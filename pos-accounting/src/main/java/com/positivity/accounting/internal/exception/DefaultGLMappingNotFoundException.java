package com.positivity.accounting.internal.exception;

/**
 * Thrown when a default GL mapping is not found by ID. Maps to HTTP 404
 * (DEFAULT_GL_MAPPING_NOT_FOUND) — ADR-0017 §1's default for a missing addressed resource
 * (issue #1694 review finding).
 */
public class DefaultGLMappingNotFoundException extends RuntimeException {

    public DefaultGLMappingNotFoundException(String message) {
        super(message);
    }
}
