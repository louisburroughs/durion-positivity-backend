package com.positivity.accounting.internal.exception;

/**
 * Thrown when a default GL mapping is not found by ID. Maps to HTTP 400
 * (DEFAULT_GL_MAPPING_NOT_FOUND) — deliberately, not 404;
 * {@code DefaultGLMappingController}'s endpoint descriptions state this
 * explicitly ("mapped as VALIDATION_ERROR, not 404").
 */
public class DefaultGLMappingNotFoundException extends RuntimeException {

    public DefaultGLMappingNotFoundException(String message) {
        super(message);
    }
}
