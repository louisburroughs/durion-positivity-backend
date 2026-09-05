package com.positivity.referencemock.internal.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Catch-all error rendering for the mock vendor (issue #1471 rule: every module with REST
 * controllers has a catch-all handler).
 *
 * <p>This is deliberately NOT the platform {@code ApiError} envelope from pos-web-common:
 * the mock simulates an EXTERNAL vendor outside the platform mesh (sourcing plan §10), so it
 * speaks a vendor-shaped error body — which is also a truer test double, since adapters must
 * survive vendor error shapes, not platform ones. What the rule actually protects against
 * still holds: no unmapped exception escapes as Spring's bare default 500 page, and every
 * error carries a reference id that shows up in the mock's own log for correlation.
 *
 * <p>The response also carries the {@code X-Correlation-Id} response header (ADR-0017 §4, issue
 * #1729): echoes the inbound request header when present and non-blank, otherwise generates a
 * fresh id. This module has no dependency on {@code pos-shared-dtos}, so neither the platform
 * {@link com.positivity.shared.error.ApiError} body (this vendor body is intentionally not that
 * shape, see above) nor {@code com.positivity.shared.id.UUIDv7Generator} is available here; the
 * generated fallback therefore uses {@link UUID#randomUUID()} (UUID v4) rather than the
 * platform's UUID v7 standard. The private {@code respond} helper is the sole path that builds a
 * response, so it cannot be bypassed.
 */
@Slf4j
@RestControllerAdvice
public class VendorErrorAdvice {

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception e, HttpServletRequest request) {
        String referenceId = UUID.randomUUID().toString();
        log.error("Mock vendor unhandled error, referenceId={}", referenceId, e);
        // No timestamp on purpose: the platform time rules route every clock read through the
        // shared TimeSource in pos-events, a dependency this vendor simulator must not take —
        // the logged reference id is the correlation device, and log lines carry the time.
        return respond(
                HttpStatus.INTERNAL_SERVER_ERROR,
                Map.of(
                        "error",
                        "VENDOR_INTERNAL_ERROR",
                        "message",
                        "The labor guide service encountered an unexpected error.",
                        "referenceId",
                        referenceId),
                request);
    }

    /**
     * Builds the response, carrying the correlation id in the {@code X-Correlation-Id} response
     * header (ADR-0017 §4). This is the only path in this advice that builds a response, so a
     * handler added later cannot forget the header.
     */
    private ResponseEntity<Map<String, Object>> respond(
            HttpStatus status, Map<String, Object> body, HttpServletRequest request) {
        return ResponseEntity.status(status)
                .header(CORRELATION_ID_HEADER, correlationId(request))
                .body(body);
    }

    private String correlationId(HttpServletRequest request) {
        String inbound = request.getHeader(CORRELATION_ID_HEADER);
        return inbound == null || inbound.isBlank() ? UUID.randomUUID().toString() : inbound;
    }
}
