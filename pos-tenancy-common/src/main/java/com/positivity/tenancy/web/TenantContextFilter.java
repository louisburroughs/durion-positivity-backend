package com.positivity.tenancy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.TenancyProperties;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.TenantHeaders;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds {@link TenantContext} for the request from the gateway's {@value TenantHeaders#HTTP_TENANT_ID}
 * header (ADR-0062 §3, request path). Runs before the security filter chain so every downstream
 * filter, handler and repository call sees the tenant, and clears it when the request ends.
 *
 * <p>Resolution order: the header (the gateway strips any inbound copy and injects it from the JWT
 * {@code tid} claim, plan WS2b), else the transitional default tenant. When neither applies and
 * {@code pos.tenancy.enforce} is on, the request is refused with a 401 {@link ApiError} (code
 * {@value #ERROR_CODE}) rather than reaching a handler unscoped; a malformed header is refused the
 * same way. Infrastructure paths (actuator, OpenAPI) are never refused, only bound when possible.
 */
public class TenantContextFilter extends OncePerRequestFilter {

    public static final String ERROR_CODE = "TENANT_REQUIRED";

    private static final Logger log = LoggerFactory.getLogger(TenantContextFilter.class);
    private static final String X_CORRELATION_ID = "X-Correlation-Id";
    private static final String[] UNENFORCED_PREFIXES = {
        "/actuator", "/swagger-ui", "/v3/api-docs", "/api-docs", "/webjars", "/error"
    };

    private final TenancyProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public TenantContextFilter(TenancyProperties properties, ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(TenantHeaders.HTTP_TENANT_ID);
        Optional<UUID> tenant;
        if (header != null && !header.isBlank()) {
            tenant = parse(header.trim());
            if (tenant.isEmpty()) {
                refuse(request, response, "Malformed " + TenantHeaders.HTTP_TENANT_ID + " header");
                return;
            }
        } else {
            tenant = properties.getDefaultTenantId();
        }

        if (tenant.isEmpty() && properties.isEnforce() && !isUnenforced(request.getRequestURI())) {
            refuse(request, response, "No tenant bound to the request");
            return;
        }

        // Bound or not, the thread ends the request unbound: a servlet thread is pooled too.
        TenantContext.clear();
        tenant.ifPresent(TenantContext::bind);
        try {
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private static Optional<UUID> parse(String raw) {
        try {
            return Optional.of(UUID.fromString(raw));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private static boolean isUnenforced(@Nullable String path) {
        if (path == null) {
            return false;
        }
        for (String prefix : UNENFORCED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        // Same convention as GlobalApiExceptionHandler: echo a trimmed inbound id, else mint a UUID v7.
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
                ERROR_CODE,
                message,
                HttpStatus.UNAUTHORIZED.value(),
                Instant.now(clock).toString(),
                correlationId);
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
