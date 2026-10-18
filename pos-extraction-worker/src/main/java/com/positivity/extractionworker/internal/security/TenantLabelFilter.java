package com.positivity.extractionworker.internal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Takes the caller's {@code X-Tenant-Id} as a log and metric label, nothing more (S26 ruling 13): it is
 * never authorisation and is never stored. A missing or non-UUID header is 401 {@value #CODE_TENANT_REQUIRED}.
 * The value is parsed as a UUID and re-rendered, so nothing else a caller sends reaches a log line or a
 * metric tag. Runs after {@link WorkerSecretFilter}, so an unauthenticated caller learns nothing, and
 * clears the MDC in {@code finally}.
 */
public class TenantLabelFilter extends OncePerRequestFilter {

    public static final String TENANT_HEADER = "X-Tenant-Id";
    public static final String TENANT_ATTRIBUTE = "extractionWorker.tenantLabel";
    public static final String MDC_KEY = "tenantId";
    public static final String CODE_TENANT_REQUIRED = "TENANT_REQUIRED";

    private final Clock clock;
    private final ObjectMapper objectMapper;

    public TenantLabelFilter(@NonNull Clock clock, @NonNull ObjectMapper objectMapper) {
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path == null || !path.startsWith(WorkerSecretFilter.PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID tenant = parse(request.getHeader(TENANT_HEADER));
        if (tenant == null) {
            ApiErrorWriter.unauthorized(
                    request,
                    response,
                    clock,
                    objectMapper,
                    CODE_TENANT_REQUIRED,
                    "X-Tenant-Id is required and must be a UUID");
            return;
        }
        request.setAttribute(TENANT_ATTRIBUTE, tenant.toString());
        MDC.put(MDC_KEY, tenant.toString());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    private static UUID parse(String header) {
        if (header == null || header.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
