package com.positivity.extractionworker.internal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Shared-secret guard for the worker API ({@code /extraction-worker/v1/**}, ADR-0014). The caller is
 * pos-accounting, not a user: the request carries {@value #SECRET_HEADER}, compared in constant time
 * against {@code pos.extraction-worker.api-secret}. A blank configured secret answers 401
 * {@value #CODE_SECRET_MISSING} and a missing or wrong header 401 {@value #CODE_SECRET_INVALID}
 * (fail closed). Neither path reads the request body, so an unauthenticated caller makes the worker parse
 * and write nothing. A match authenticates the request as {@value #PRINCIPAL}.
 *
 * <p>Runs inside the security filter chain (see {@code SecurityConfig}), never as a plain servlet filter,
 * as pos-platform-sender's {@code SenderSecretFilter} does.
 */
@Slf4j
public class WorkerSecretFilter extends OncePerRequestFilter {

    public static final String SECRET_HEADER = "X-Extraction-Worker-Secret";
    public static final String PATH_PREFIX = "/extraction-worker/v1";
    public static final String PRINCIPAL = "extraction-worker-client";
    public static final String CODE_SECRET_MISSING = "EXTRACTION_WORKER_SECRET_MISSING";
    public static final String CODE_SECRET_INVALID = "INVALID_EXTRACTION_WORKER_SECRET";

    private final @Nullable String expectedSecret;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public WorkerSecretFilter(
            @Nullable String expectedSecret, @NonNull Clock clock, @NonNull ObjectMapper objectMapper) {
        this.expectedSecret = expectedSecret;
        this.clock = clock;
        this.objectMapper = objectMapper;
        if (expectedSecret == null || expectedSecret.isBlank()) {
            log.warn("pos.extraction-worker.api-secret is not set: {} refuses every request", PATH_PREFIX);
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
            reject(request, response, CODE_SECRET_MISSING, "Extraction worker secret is not configured");
            return;
        }
        String provided = request.getHeader(SECRET_HEADER);
        boolean authorized = provided != null
                && MessageDigest.isEqual(
                        expectedSecret.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
        if (!authorized) {
            reject(request, response, CODE_SECRET_INVALID, "Invalid or missing extraction worker secret");
            return;
        }
        SecurityContextHolder.getContext()
                .setAuthentication(UsernamePasswordAuthenticationToken.authenticated(PRINCIPAL, null, List.of()));
        chain.doFilter(request, response);
    }

    private void reject(HttpServletRequest request, HttpServletResponse response, String code, String message)
            throws IOException {
        log.warn("{} on {} {}", code, request.getMethod(), PATH_PREFIX);
        ApiErrorWriter.unauthorized(request, response, clock, objectMapper, code, message);
    }
}
