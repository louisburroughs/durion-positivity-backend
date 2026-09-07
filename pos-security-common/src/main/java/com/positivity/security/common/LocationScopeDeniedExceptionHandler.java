package com.positivity.security.common;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Maps {@link LocationScopeDeniedException} onto the {@code ApiError} envelope with code
 * {@link LocationScopeDeniedException#ERROR_CODE} and a correlation id (ADR-0017 §3/§4, #1870).
 *
 * <p>Why a dedicated advice at {@link Ordered#HIGHEST_PRECEDENCE}: Spring picks the first advice
 * in order that has <em>any</em> handler for the exception type, not the most specific handler
 * across advices. Module advices map the parent {@code AccessDeniedException} to a plain
 * {@code FORBIDDEN}, and pos-web-common's catch-all rethrows security exceptions to the filter
 * chain, so without this advice the distinguishing code would be lost in every module. Handling
 * exactly one exception type at highest precedence changes nothing else about a module's error
 * mapping.
 *
 * <p>Registered by {@link LocationScopeAutoConfiguration} for every servlet application on this
 * library's classpath.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LocationScopeDeniedExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LocationScopeDeniedExceptionHandler.class);

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    /** Generic on purpose: the requested location id is caller-supplied and is not reflected. */
    private static final String MESSAGE = "Caller's location scope does not cover the requested location";

    private final Clock clock;

    public LocationScopeDeniedExceptionHandler(@NonNull Clock clock) {
        this.clock = clock;
    }

    /**
     * @param ex the denial
     * @param request the request, for the correlation id and the logged path
     * @param response receives the {@code X-Correlation-Id} header
     * @return a 403 {@code ApiError} with code {@code LOCATION_SCOPE_DENIED}
     */
    @ExceptionHandler(LocationScopeDeniedException.class)
    public ResponseEntity<ApiError> handleLocationScopeDenied(
            @NonNull LocationScopeDeniedException ex,
            @Nullable HttpServletRequest request,
            @NonNull HttpServletResponse response) {
        String correlationId = resolveCorrelationId(request);
        response.setHeader(X_CORRELATION_ID, correlationId);
        log.warn(
                "Location scope denied on {} [correlationId={}]: permission={} locationId={}",
                request != null ? request.getRequestURI() : "",
                correlationId,
                ex.permission(),
                LogSanitizer.forLog(ex.locationId()));
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of(
                        LocationScopeDeniedException.ERROR_CODE,
                        MESSAGE,
                        HttpStatus.FORBIDDEN.value(),
                        Instant.now(clock).toString(),
                        correlationId));
    }

    private static String resolveCorrelationId(@Nullable HttpServletRequest request) {
        String correlationId = request != null ? request.getHeader(X_CORRELATION_ID) : null;
        if (correlationId == null || correlationId.isBlank()) {
            return UUIDv7Generator.generate().toString();
        }
        return correlationId.trim();
    }
}
