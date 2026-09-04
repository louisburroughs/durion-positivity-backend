package com.positivity.accounting.internal.exception;

/**
 * Thrown when GL mapping resolution finds no configured posting
 * category, mapping key, or effective mapping for the requested
 * source/date. The caller's request is valid; the posting configuration is
 * incomplete for this case. Maps to HTTP 422 (GL_MAPPING_NOT_CONFIGURED)
 * per ADR-0017 §2 — actionable by completing the GL mapping setup, not a
 * malformed request.
 */
public class GLMappingNotConfiguredException extends RuntimeException {

    public GLMappingNotConfiguredException(String message) {
        super(message);
    }
}
