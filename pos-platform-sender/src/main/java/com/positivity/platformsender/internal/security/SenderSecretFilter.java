package com.positivity.platformsender.internal.security;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared-secret guard for the send API (FI-2 §1, {@code /platform-sender/v1/**}). The caller is
 * another service, not a user, so there is no JWT and no gateway header: the request carries
 * {@value #SECRET_HEADER}, compared in constant time against {@code pos.platform-sender.api-secret}.
 * A blank configured secret or a wrong header is a 401 {@link ApiError} (fail closed); a matching
 * one authenticates the request as {@value #PRINCIPAL} so the send chain's {@code authenticated()}
 * rule passes.
 *
 * <p>The tenant is not this filter's business: {@code TenantContextFilter} has already bound it from
 * the caller's {@code X-Tenant-Id} by the time the security chain runs.
 *
 * <p>Runs inside the security filter chain (see {@code SecurityConfig}), never as a plain servlet
 * filter: an authentication set ahead of {@code SecurityContextHolderFilter} would be discarded.
 * Same pattern as pos-tenant's {@code TenantRegistrySecretFilter}.
 */
@Slf4j
public class SenderSecretFilter extends OncePerRequestFilter {

    public static final String SECRET_HEADER = "X-Pos-Sender-Secret";
    public static final String PATH_PREFIX = "/platform-sender/v1";
    public static final String PRINCIPAL = "platform-sender-client";
    public static final String CODE_SECRET_MISSING = "PLATFORM_SENDER_SECRET_MISSING";
    public static final String CODE_SECRET_INVALID = "INVALID_PLATFORM_SENDER_SECRET";

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    private final @Nullable String expectedSecret;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public SenderSecretFilter(
            @Nullable String expectedSecret, @NonNull Clock clock, @NonNull ObjectMapper objectMapper) {
        this.expectedSecret = expectedSecret;
        this.clock = clock;
        this.objectMapper = objectMapper;
        if (expectedSecret == null || expectedSecret.isBlank()) {
            log.warn("pos.platform-sender.api-secret is not set: {} refuses every request", PATH_PREFIX);
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (expectedSecret == null || expectedSecret.isBlank()) {
            reject(request, response, CODE_SECRET_MISSING, "Platform sender secret is not configured");
            return;
        }
        String provided = request.getHeader(SECRET_HEADER);
        boolean authorized = provided != null
                && MessageDigest.isEqual(
                        expectedSecret.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
        if (!authorized) {
            reject(request, response, CODE_SECRET_INVALID, "Invalid or missing platform sender secret");
            return;
        }
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null, List.of()));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String code, String message)
            throws IOException {
        String inbound = request.getHeader(X_CORRELATION_ID);
        String correlationId = inbound == null || inbound.isBlank()
                ? UUIDv7Generator.generate().toString()
                : inbound.trim();
        log.warn(
                "{} on {} {} [correlationId={}]", message, request.getMethod(), request.getRequestURI(), correlationId);
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
