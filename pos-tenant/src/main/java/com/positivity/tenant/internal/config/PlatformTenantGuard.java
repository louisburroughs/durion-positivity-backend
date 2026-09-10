package com.positivity.tenant.internal.config;

import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import com.positivity.tenancy.PlatformTenant;
import com.positivity.tenancy.TenantContext;
import com.positivity.tenancy.autoconfigure.TenancyWebAutoConfiguration;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Only platform staff reach this module (ADR-0062 §7): a request bound to any tenant other than
 * {@link PlatformTenant#ID} is refused with a 403 {@link ApiError} (code {@value #ERROR_CODE})
 * before any handler runs. Registered right after {@code TenantContextFilter}, which binds the
 * tenant from the gateway's {@code X-Tenant-Id} (or the platform default until plan WS2b), and
 * ahead of Spring Security, so the refusal never depends on which authorities the caller holds.
 *
 * <p>Requests with no tenant bound are left to {@code TenantContextFilter}'s own rule (a 401 on
 * enforced paths, pass-through on actuator and OpenAPI paths).
 */
@Slf4j
@Configuration
public class PlatformTenantGuard {

    public static final String ERROR_CODE = "PLATFORM_TENANT_REQUIRED";

    /** Just after the tenant filter (-110), still ahead of Spring Security (-100). */
    static final int FILTER_ORDER = TenancyWebAutoConfiguration.FILTER_ORDER + 1;

    private static final String X_CORRELATION_ID = "X-Correlation-Id";

    @Bean
    public FilterRegistrationBean<PlatformTenantFilter> platformTenantFilter(ObjectMapper objectMapper, Clock clock) {
        FilterRegistrationBean<PlatformTenantFilter> registration =
                new FilterRegistrationBean<>(new PlatformTenantFilter(objectMapper, clock));
        registration.setOrder(FILTER_ORDER);
        registration.addUrlPatterns("/*");
        return registration;
    }

    /** The filter itself; package-visible so the unit test can drive it without a context. */
    static final class PlatformTenantFilter extends OncePerRequestFilter {

        private final ObjectMapper objectMapper;
        private final Clock clock;

        PlatformTenantFilter(ObjectMapper objectMapper, Clock clock) {
            this.objectMapper = objectMapper;
            this.clock = clock;
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            UUID bound = TenantContext.current().orElse(null);
            if (bound != null && !PlatformTenant.isPlatform(bound)) {
                log.warn(
                        "Refusing {} {} bound to non-platform tenant {}",
                        request.getMethod(),
                        request.getRequestURI(),
                        bound);
                reject(request, response);
                return;
            }
            chain.doFilter(request, response);
        }

        private void reject(HttpServletRequest request, HttpServletResponse response) throws IOException {
            String inbound = request.getHeader(X_CORRELATION_ID);
            String correlationId = inbound == null || inbound.isBlank()
                    ? UUIDv7Generator.generate().toString()
                    : inbound.trim();
            response.setStatus(HttpStatus.FORBIDDEN.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setHeader(X_CORRELATION_ID, correlationId);
            ApiError body = ApiError.of(
                    ERROR_CODE,
                    "The tenant registry is reachable from the platform tenant only",
                    HttpStatus.FORBIDDEN.value(),
                    Instant.now(clock).toString(),
                    correlationId);
            objectMapper.writeValue(response.getOutputStream(), body);
        }
    }
}
