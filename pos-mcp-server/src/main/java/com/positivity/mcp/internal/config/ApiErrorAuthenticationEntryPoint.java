package com.positivity.mcp.internal.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

/**
 * Writes an {@link ApiError} JSON body for unauthenticated requests rejected by
 * the Spring Security filter chain, in compliance with ADR-0017.
 *
 * <p>
 * {@link org.springframework.web.bind.annotation.RestControllerAdvice} cannot
 * catch filter-chain rejections; this entry point fills that gap.
 * </p>
 */
class ApiErrorAuthenticationEntryPoint implements AuthenticationEntryPoint {

  static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

  private final ObjectMapper objectMapper;
  private final Clock clock;

  ApiErrorAuthenticationEntryPoint(@NonNull ObjectMapper objectMapper, @NonNull Clock clock) {
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public void commence(
      @NonNull HttpServletRequest request,
      @NonNull HttpServletResponse response,
      @NonNull AuthenticationException authException) throws IOException {
    UUID correlationId = resolveCorrelationId(request);
    ApiError error = ApiError.of(
        "UNAUTHORIZED",
        "Authentication is required",
        HttpStatus.UNAUTHORIZED.value(),
        Instant.now(clock).toString(),
        correlationId.toString());
    response.setStatus(HttpStatus.UNAUTHORIZED.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader(CORRELATION_ID_HEADER, correlationId.toString());
    objectMapper.writeValue(response.getOutputStream(), error);
  }

  private UUID resolveCorrelationId(HttpServletRequest request) {
    String incoming = request.getHeader(CORRELATION_ID_HEADER);
    if (incoming != null && !incoming.isBlank()) {
      try {
        return UUID.fromString(incoming);
      } catch (IllegalArgumentException _) {
        // fall through
      }
    }
    return UUIDv7Generator.generate();
  }
}