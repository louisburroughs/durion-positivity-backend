package com.positivity.platformsender.internal.config;

import com.positivity.platformsender.internal.exception.MessageRefusedException;
import com.positivity.platformsender.internal.exception.SenderUnavailableException;
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
 * Maps the send API's outcomes onto FI-2 §1's status classes (ADR-0017 envelope): a permanent
 * refusal is a {@code 422} the caller does not retry, a transient failure a {@code 503} it does.
 * Anything unexpected falls through to {@code pos-web-common}'s {@code GlobalApiExceptionHandler},
 * whose {@code 500} the caller also treats as transient.
 */
@ControllerAdvice
@Slf4j
@RequiredArgsConstructor
public class SenderExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private final Clock clock;

    @ExceptionHandler(MessageRefusedException.class)
    public ResponseEntity<ApiError> handleRefused(
            MessageRefusedException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Send refused on {} code={}: {}", path(request), ex.getCode(), ex.getMessage());
        return error(request, response, HttpStatus.UNPROCESSABLE_ENTITY, ex.getCode(), ex.getMessage());
    }

    @ExceptionHandler(SenderUnavailableException.class)
    public ResponseEntity<ApiError> handleUnavailable(
            SenderUnavailableException ex, HttpServletRequest request, HttpServletResponse response) {
        log.warn("Send not completed on {} code={}: {}", path(request), ex.getCode(), ex.getMessage());
        return error(request, response, HttpStatus.SERVICE_UNAVAILABLE, ex.getCode(), ex.getMessage());
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
                : correlationId;
    }
}
