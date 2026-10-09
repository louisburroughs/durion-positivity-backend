package com.positivity.tax.internal.security;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenantHeaders;
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
import java.util.UUID;
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
 * The front-door guard on pos-tax's tax-registration writes (CAP:550 S32c; ADR-0071 §5-6, the pos-platform-sender
 * pattern). {@code /v1/tax/registrations/**} accepts exactly one caller, pos-accounting, authenticated by its
 * per-caller shared secret in {@value #SECRET_HEADER}, compared in constant time with
 * {@code pos.tax.front-doors.accounting-secret}. A person never reaches it: the person's permission lives at the
 * front door ({@code accounting:tax_registration:manage}), so no {@code tax:registration:*} key exists.
 *
 * <p>Every refusal is a 401 {@link ApiError} and the chain never runs (fail closed, ADR-0017 §1):
 * <ul>
 *   <li>a blank configured secret refuses every request ({@value #CODE_SECRET_MISSING});</li>
 *   <li>a missing or wrong secret ({@value #CODE_SECRET_INVALID});</li>
 *   <li>a request without the forwarded actor or the forwarded tenant ({@value #CODE_CONTEXT_MISSING}).</li>
 * </ul>
 *
 * <p>The actor comes only from the forwarded {@value #ACTOR_HEADER}, which the front door itself received from the
 * gateway; it is trusted only on a request that carries the front door's secret, and is bound as the request's
 * principal (ADR-0018), so the history row names the person who asked. A body field naming another actor is never
 * read. The tenant comes only from the forwarded {@code X-Tenant-Id}, which {@code TenantContextFilter} has already
 * bound (ADR-0062); this filter requires the header itself so a missing one can never fall back to a default tenant.
 *
 * <p>Runs inside its own security filter chain ({@code SecurityConfig}), never as a plain servlet filter.
 */
@Slf4j
public class FrontDoorSecretFilter extends OncePerRequestFilter {

    public static final String SECRET_HEADER = "X-Pos-Tax-Front-Door-Secret";
    public static final String ACTOR_HEADER = "X-User-Id";
    public static final String PATH_PREFIX = "/v1/tax/registrations";
    public static final String CODE_SECRET_MISSING = "TAX_FRONT_DOOR_SECRET_MISSING";
    public static final String CODE_SECRET_INVALID = "INVALID_TAX_FRONT_DOOR_SECRET";
    public static final String CODE_CONTEXT_MISSING = "TAX_FRONT_DOOR_CONTEXT_MISSING";

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    private final @Nullable String expectedSecret;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public FrontDoorSecretFilter(
            @Nullable String expectedSecret, @NonNull Clock clock, @NonNull ObjectMapper objectMapper) {
        this.expectedSecret = expectedSecret;
        this.clock = clock;
        this.objectMapper = objectMapper;
        if (expectedSecret == null || expectedSecret.isBlank()) {
            log.warn("pos.tax.front-doors.accounting-secret is not set: {} refuses every request", PATH_PREFIX);
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
            reject(request, response, CODE_SECRET_MISSING, "The tax front-door secret is not configured");
            return;
        }
        String provided = request.getHeader(SECRET_HEADER);
        boolean authorized = provided != null
                && MessageDigest.isEqual(
                        expectedSecret.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
        if (!authorized) {
            reject(request, response, CODE_SECRET_INVALID, "Invalid or missing tax front-door secret");
            return;
        }
        UUID actor = parseUuid(request.getHeader(ACTOR_HEADER));
        UUID tenant = parseUuid(request.getHeader(TenantHeaders.HTTP_TENANT_ID));
        if (actor == null || tenant == null) {
            reject(
                    request,
                    response,
                    CODE_CONTEXT_MISSING,
                    "The front door must forward the actor (X-User-Id) and the tenant (X-Tenant-Id)");
            return;
        }
        SecurityContextHolder.getContext()
                .setAuthentication(
                        UsernamePasswordAuthenticationToken.authenticated(actor.toString(), null, List.of()));
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static @Nullable UUID parseUuid(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
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
