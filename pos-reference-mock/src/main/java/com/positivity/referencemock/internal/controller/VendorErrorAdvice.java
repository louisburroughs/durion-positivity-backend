package com.positivity.referencemock.internal.controller;

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
 */
@Slf4j
@RestControllerAdvice
public class VendorErrorAdvice {

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleAny(Exception e) {
        String referenceId = UUID.randomUUID().toString();
        log.error("Mock vendor unhandled error, referenceId={}", referenceId, e);
        // No timestamp on purpose: the platform time rules route every clock read through the
        // shared TimeSource in pos-events, a dependency this vendor simulator must not take —
        // the logged reference id is the correlation device, and log lines carry the time.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "error",
                        "VENDOR_INTERNAL_ERROR",
                        "message",
                        "The labor guide service encountered an unexpected error.",
                        "referenceId",
                        referenceId));
    }
}
