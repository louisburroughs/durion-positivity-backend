package com.positivity.mcp.internal.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Writes an {@link ApiError} JSON body for requests the Spring Security filter chain itself
 * denies (#2074), for example {@code mcpApiSecurityFilterChain}'s {@code requestMatchers(...)}
 * rule for {@code POST /v1/mcp/transcriptions}.
 *
 * <p>Mirrors {@link ApiErrorAuthenticationEntryPoint}: {@link
 * org.springframework.web.bind.annotation.RestControllerAdvice} (used by {@code
 * McpTranscriptionExceptionHandler#handleAccessDenied} for the {@code @PreAuthorize} case) cannot
 * catch a filter-chain rejection — there is no resolved handler yet for {@code
 * ExceptionHandlerExceptionResolver} to match against. Without this handler Spring Security's
 * default {@code AccessDeniedHandlerImpl} would answer with a bare, bodyless 403, breaking the
 * ADR-0017 envelope for exactly the callers this filter-chain rule exists to reject early. The
 * body matches {@code McpTranscriptionExceptionHandler#handleAccessDenied} exactly so a caller
 * cannot distinguish a method-security denial from a filter-chain one.
 */
class ApiErrorAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    ApiErrorAccessDeniedHandler(@NonNull ObjectMapper objectMapper, @NonNull Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public void handle(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull AccessDeniedException accessDeniedException)
            throws IOException {
        UUID correlationId = resolveCorrelationId(request);
        ApiError error = ApiError.of(
                "FORBIDDEN",
                "Insufficient permissions",
                HttpStatus.FORBIDDEN.value(),
                Instant.now(clock).toString(),
                correlationId.toString());
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader(ApiErrorAuthenticationEntryPoint.CORRELATION_ID_HEADER, correlationId.toString());
        objectMapper.writeValue(response.getOutputStream(), error);
    }

    private UUID resolveCorrelationId(HttpServletRequest request) {
        String incoming = request.getHeader(ApiErrorAuthenticationEntryPoint.CORRELATION_ID_HEADER);
        if (incoming != null && !incoming.isBlank()) {
            try {
                return UUID.fromString(incoming);
            } catch (IllegalArgumentException ignored) {
                // fall through
            }
        }
        return UUIDv7Generator.generate();
    }
}
