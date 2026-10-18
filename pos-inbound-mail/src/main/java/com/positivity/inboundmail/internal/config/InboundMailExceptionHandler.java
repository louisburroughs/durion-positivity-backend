package com.positivity.inboundmail.internal.config;

import com.positivity.inboundmail.internal.exception.StagedAttachmentNotFoundException;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * Maps the edge API's outcomes onto the ADR-0017 envelope. A staged-attachment ref the caller's tenant
 * cannot read answers one fixed 404 whatever the reason (unknown, another tenant's, collected, purged),
 * so the body never tells a probing caller which. Anything unexpected falls through to {@code
 * pos-web-common}'s {@code GlobalApiExceptionHandler}.
 */
@ControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class InboundMailExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(StagedAttachmentNotFoundException.class)
    public ResponseEntity<ApiError> handleStagedAttachmentNotFound(
            StagedAttachmentNotFoundException ex, HttpServletRequest request, HttpServletResponse response) {
        // No ref in the log line: the 404 carries no information and neither does its log.
        log.debug("Staged attachment fetch answered 404 on {}", path(request));
        return error(request, response, HttpStatus.NOT_FOUND, StagedAttachmentNotFoundException.CODE, ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Validation failed on {}: {}", path(request), ex.getMessage());
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        List<ApiError.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(
                        fe.getField(), fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "Invalid value"))
                .toList();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiError.withFieldErrors(
                        "VALIDATION_FAILED",
                        "Request validation failed",
                        HttpStatus.BAD_REQUEST.value(),
                        Instant.now(clock).toString(),
                        correlationId,
                        fieldErrors));
    }

    private ResponseEntity<ApiError> error(
            HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code, String message) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        return ResponseEntity.status(status)
                .body(ApiError.of(
                        code, message, status.value(), Instant.now(clock).toString(), correlationId));
    }

    private static String path(HttpServletRequest request) {
        return request != null ? request.getRequestURI() : "";
    }

    private String resolveCorrelationId(HttpServletRequest request) {
        if (request == null) {
            return UUIDv7Generator.generate().toString();
        }
        String correlationId = request.getHeader(X_CORRELATION_ID);
        return correlationId == null || correlationId.isBlank()
                ? UUIDv7Generator.generate().toString()
                : correlationId.trim();
    }
}
