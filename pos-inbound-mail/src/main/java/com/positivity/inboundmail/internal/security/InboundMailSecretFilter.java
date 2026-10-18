package com.positivity.inboundmail.internal.security;

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
 * Shared-secret guard for the edge API ({@code /inbound-mail/v1/**}). The caller is another service
 * (pos-accounting), not a user, so there is no JWT and no gateway header: the request carries
 * {@value #SECRET_HEADER}, compared in constant time against {@code pos.inbound-mail.api-secret}.
 * A blank configured secret is a 401 {@value #CODE_SECRET_MISSING} (the edge's own configuration is
 * missing, every request is refused); a missing or wrong header is a 401 {@value #CODE_SECRET_INVALID};
 * a matching one authenticates the request as {@value #PRINCIPAL} so the chain's {@code
 * authenticated()} rule passes. Same fail-closed meanings as pos-extraction-worker (S26 ruling 14).
 *
 * <p>The tenant is not this filter's business: {@code TenantContextFilter} has already bound it from
 * {@code X-Tenant-Id}, and answered 401 {@code TENANT_REQUIRED} when it is missing (the edge has no
 * default tenant). The filter never reads the request body.
 *
 * <p>Runs inside the security filter chain (see {@code SecurityConfig}), never as a plain servlet
 * filter: an authentication set ahead of {@code SecurityContextHolderFilter} would be discarded.
 */
@Slf4j
public class InboundMailSecretFilter extends OncePerRequestFilter {

    public static final String SECRET_HEADER = "X-Inbound-Mail-Secret";
    public static final String PATH_PREFIX = "/inbound-mail/v1";
    public static final String PRINCIPAL = "inbound-mail-client";
    public static final String CODE_SECRET_MISSING = "INBOUND_MAIL_SECRET_MISSING";
    public static final String CODE_SECRET_INVALID = "INVALID_INBOUND_MAIL_SECRET";

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    private final @Nullable String expectedSecret;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public InboundMailSecretFilter(
            @Nullable String expectedSecret, @NonNull Clock clock, @NonNull ObjectMapper objectMapper) {
        this.expectedSecret = expectedSecret;
        this.clock = clock;
        this.objectMapper = objectMapper;
        if (expectedSecret == null || expectedSecret.isBlank()) {
            log.warn("pos.inbound-mail.api-secret is not set: {} refuses every request", PATH_PREFIX);
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
            reject(request, response, CODE_SECRET_MISSING, "Inbound mail secret is not configured");
            return;
        }
        String provided = request.getHeader(SECRET_HEADER);
        boolean authorized = provided != null
                && MessageDigest.isEqual(
                        expectedSecret.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
        if (!authorized) {
            reject(request, response, CODE_SECRET_INVALID, "Invalid or missing inbound mail secret");
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
        // Method and path only: the path of a staged-attachment fetch carries a ref, never an address.
        log.warn("{} on {} {} [correlationId={}]", message, request.getMethod(), request.getRequestURI(), correlationId);
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
