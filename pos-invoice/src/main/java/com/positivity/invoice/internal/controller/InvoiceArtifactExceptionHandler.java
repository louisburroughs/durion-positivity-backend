package com.positivity.invoice.internal.controller;

import com.positivity.invoice.internal.exception.InvoiceNotFoundException;
import com.positivity.invoice.internal.service.ArtifactTokenService.InvalidTokenException;
import com.positivity.invoice.internal.service.InvoiceArtifactService.ArtifactNotFoundException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps invoice-artifact errors to HTTP statuses for the artifact controllers. */
@RestControllerAdvice(assignableTypes = {InvoiceArtifactController.class, InvoiceArtifactDownloadController.class})
@RequiredArgsConstructor
public class InvoiceArtifactExceptionHandler {
    private final Clock clock;

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @ExceptionHandler({InvoiceNotFoundException.class, ArtifactNotFoundException.class})
    public ResponseEntity<ApiError> handleNotFound(RuntimeException ex, HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage(), request);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ApiError> handleInvalidToken(InvalidTokenException ex, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleBadRequest(IllegalArgumentException ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", ex.getMessage(), request);
    }

    private ResponseEntity<ApiError> error(HttpStatus status, String code, String message, HttpServletRequest request) {
        String correlationId = correlationId(request);
        return ResponseEntity.status(status)
                .header(X_CORRELATION_ID, correlationId)
                .body(ApiError.of(
                        code, message, status.value(), Instant.now(clock).toString(), correlationId));
    }

    private static String correlationId(HttpServletRequest request) {
        String header = request.getHeader(X_CORRELATION_ID);
        return (header != null && !header.isBlank())
                ? header
                : UUIDv7Generator.generate().toString();
    }
}
