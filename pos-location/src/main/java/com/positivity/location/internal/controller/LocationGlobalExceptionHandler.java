package com.positivity.location.internal.controller;

import com.positivity.shared.id.UUIDv7Generator;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Module-wide exception advice rendering RFC 9457 ProblemDetail bodies for
 * {@code ResponseStatusException} and standard Spring MVC exceptions.
 *
 * <p>Without an advice, MockMvc-observed error responses have empty bodies
 * (the servlet /error dispatch is not followed in tests) and runtime bodies
 * fall back to the Boot default error JSON. Consumers such as the
 * pos-inventory rollup client rely on a deterministic error envelope
 * (CAP-214 #655 — "404 ProblemDetail"; PR #661 review finding 1).
 *
 * <p>Every response the inherited handlers build passes through
 * {@link #createResponseEntity}, which is overridden so that each one carries the
 * correlation id in the {@code X-Correlation-Id} response header and, on a
 * {@link ProblemDetail} body, as the {@code correlationId} property (ADR-0017 §4,
 * issue #1729): an inbound {@code X-Correlation-Id} is echoed, otherwise a UUIDv7 is
 * generated. The body shape is unchanged.
 */
@RestControllerAdvice
public class LocationGlobalExceptionHandler extends ResponseEntityExceptionHandler {

    static final String X_CORRELATION_ID = "X-Correlation-Id";

    @Override
    protected ResponseEntity<Object> createResponseEntity(
            @Nullable Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        String correlationId = resolveCorrelationId(request);
        HttpHeaders correlated = new HttpHeaders();
        correlated.putAll(headers);
        correlated.set(X_CORRELATION_ID, correlationId);
        if (body instanceof ProblemDetail problem) {
            problem.setProperty("correlationId", correlationId);
        }
        return super.createResponseEntity(body, correlated, statusCode, request);
    }

    private static String resolveCorrelationId(WebRequest request) {
        String inbound = request.getHeader(X_CORRELATION_ID);
        return inbound == null || inbound.isBlank() ? UUIDv7Generator.generate().toString() : inbound.trim();
    }
}
