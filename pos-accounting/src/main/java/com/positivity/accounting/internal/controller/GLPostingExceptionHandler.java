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
 * <p>Responses carry a correlation id in both the {@code ApiError} body and the
 * {@code X-Correlation-Id} response header, per ADR-0017 §4: an inbound header is echoed, and a
 * UUIDv7 is generated when the client sent none. This mirrors the {@code build()}/
 * {@code resolveCorrelationId()} pair in {@code AccountingExceptionHandler} and
 * {@link APPaymentExceptionHandler} — this advice was outside the scope of the #1694 fix that
 * introduced it there and used to answer {@code correlationId: null} with no header at all
 * (issue #1719), leaving GL posting failures, the failures most worth tracing, untraceable.
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
