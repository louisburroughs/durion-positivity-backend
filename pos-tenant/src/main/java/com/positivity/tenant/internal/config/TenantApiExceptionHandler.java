package com.positivity.tenant.internal.config;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenant.internal.exception.ReadingTierExistsException;
import com.positivity.tenant.internal.exception.ReadingTierInUseException;
import com.positivity.tenant.internal.exception.ReadingTierNotAssignableException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The named error codes of the reading-tier API (#2619; ADR-0017). Everything else stays with pos-web-common's
 * {@code GlobalApiExceptionHandler}, which maps a {@code @ResponseStatus} exception to a coarse code (CONFLICT,
 * UNPROCESSABLE_CONTENT); the operator and the frontend branch on these three, so they are named.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
@RequiredArgsConstructor
public class TenantApiExceptionHandler {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    private final Clock clock;

    @ExceptionHandler(ReadingTierExistsException.class)
    public ResponseEntity<ApiError> readingTierExists(ReadingTierExistsException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ReadingTierExistsException.CODE, ex.getMessage(), request);
    }

    @ExceptionHandler(ReadingTierInUseException.class)
    public ResponseEntity<ApiError> readingTierInUse(ReadingTierInUseException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ReadingTierInUseException.CODE, ex.getMessage(), request);
    }

    @ExceptionHandler(ReadingTierNotAssignableException.class)
    public ResponseEntity<ApiError> readingTierNotAssignable(
            ReadingTierNotAssignableException ex, HttpServletRequest request) {
        return build(
                HttpStatus.UNPROCESSABLE_CONTENT, ReadingTierNotAssignableException.CODE, ex.getMessage(), request);
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message, HttpServletRequest request) {
        String header = request == null ? null : request.getHeader(X_CORRELATION_ID);
        String correlationId =
                header != null && !header.isBlank() ? header : UUIDv7Generator.generate().toString();
        HttpHeaders headers = new HttpHeaders();
        headers.add(X_CORRELATION_ID, correlationId);
        return new ResponseEntity<>(
                ApiError.of(code, message, status.value(), Instant.now(clock).toString(), correlationId),
                headers,
                status);
    }
}
