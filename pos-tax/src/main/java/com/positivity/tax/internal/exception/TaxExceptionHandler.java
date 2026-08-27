package com.positivity.tax.internal.exception;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Module-local translation of pos-tax domain exceptions into the standard {@code ApiError}
 * envelope ({@code docs/ERROR_ENVELOPE.md}) for cases the platform-wide
 * {@code GlobalApiExceptionHandler} (pos-web-common) cannot express on its own:
 * <ul>
 *   <li>A 5xx status with a module-specific code and a message naming the offending
 *       provider — the fallback handler only assigns generic codes/messages to 5xx
 *       {@code ErrorResponse}s and does not special-case a thrown exception's own message.</li>
 *   <li>{@link ConstraintViolationException} from {@code @Validated} query-parameter
 *       constraints (the jurisdiction rate lookup's {@code countryCode}/{@code postalCode}),
 *       which the fallback handler has no explicit mapping for and would otherwise collapse
 *       to a 500.</li>
 * </ul>
 * Everything else (body validation via {@code @Valid}, {@code ResponseStatusException} 404s,
 * malformed JSON, etc.) continues to fall through to the pos-web-common fallback unchanged.
 */
@RestControllerAdvice(basePackages = "com.positivity.tax.internal.controller")
public class TaxExceptionHandler {

    private static final String VALIDATION_ERROR = "VALIDATION_ERROR";
    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    private final Clock clock;

    public TaxExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /**
     * The configured tax provider does not support rate-only lookup (issue #1522): an honest
     * 501 naming the active provider, rather than a synthetic estimate.
     */
    @ExceptionHandler(TaxRateLookupUnsupportedException.class)
    public ResponseEntity<ApiError> handleRateLookupUnsupported(
            TaxRateLookupUnsupportedException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_IMPLEMENTED, "TAX_RATE_LOOKUP_UNSUPPORTED", ex.getMessage(), request);
    }

    /** {@code @Validated} query-parameter constraint failures (e.g. an invalid countryCode). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, VALIDATION_ERROR, "Request validation failed", request);
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
