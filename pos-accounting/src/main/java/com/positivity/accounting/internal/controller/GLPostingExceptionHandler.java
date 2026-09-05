package com.positivity.accounting.internal.controller;

import com.positivity.accounting.internal.exception.GLPostingException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Exception handler for GL Posting failures.
 *
 * <p>Every response built by this advice carries the correlation id in both the {@link ApiError}
 * body and the {@code X-Correlation-Id} response header (ADR-0017 §4, issue #1729). The private
 * {@code build} helper is the sole path that builds an {@link ApiError}, so a handler added later
 * cannot forget the header. Previously this advice passed a hardcoded {@code null} correlation id
 * and never set the header at all.
 */
@RestControllerAdvice(basePackages = "com.positivity.accounting.internal.controller")
@RequiredArgsConstructor
public class GLPostingExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GLPostingExceptionHandler.class);
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(GLPostingException.class)
    public ResponseEntity<ApiError> handleGLPostingException(GLPostingException ex, HttpServletRequest request) {
        log.error("GL Posting Exception: {}", ex.getMessage(), ex);
        return build(HttpStatus.CONFLICT, "GL_POSTING_FAILED", ex.getMessage(), request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId),
                headers,
                status);
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}
