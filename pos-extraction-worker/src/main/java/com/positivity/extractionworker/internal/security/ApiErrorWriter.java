package com.positivity.extractionworker.internal.security;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/** Writes an ADR-0017 {@link ApiError} from a filter, where no controller advice runs. Reads nothing of the body. */
final class ApiErrorWriter {

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    private ApiErrorWriter() {}

    /** The caller's correlation id, or a fresh one. */
    static String correlationId(HttpServletRequest request) {
        String inbound = request.getHeader(X_CORRELATION_ID);
        return inbound == null || inbound.isBlank()
                ? UUIDv7Generator.generate().toString()
                : inbound.trim();
    }

    static void unauthorized(
            HttpServletRequest request,
            HttpServletResponse response,
            Clock clock,
            ObjectMapper objectMapper,
            String code,
            String message)
            throws IOException {
        String correlationId = correlationId(request);
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(X_CORRELATION_ID, correlationId);
        ApiError body = ApiError.of(
                code,
                message,
                HttpStatus.UNAUTHORIZED.value(),
                Instant.now(clock).toString(),
                correlationId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
