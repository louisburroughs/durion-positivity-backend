package com.positivity.security.common;

import com.positivity.tenancy.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds {@link TenantContext} for the request from the gateway's {@code X-Tenant-Id} header
 * (ADR-0062 §3), so the datasource can put it on every connection the request borrows.
 *
 * <p>The header is trusted for the same reason {@code X-Authorities} is: the gateway derives it
 * from the validated token and strips any inbound copy, and services are reachable only through
 * the gateway. Nothing here reads a tenant from a request body, a query parameter, or any other
 * client-controlled input.
 *
 * <p>Ordered ahead of {@link GatewayAuthoritiesFilter} because binding does not depend on
 * authentication, and a separate filter keeps the security filter's four exit paths untouched.
 *
 * <p>A missing or unparseable header binds nothing, which is not an error here. Fail-closed lives
 * at the database: with no tenant bound, scoped tables read as empty and refuse inserts. Rejecting
 * the request instead would break every unauthenticated and actuator path.
 */
@Order(0)
public class TenantBindingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TenantBindingFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        boolean bound = bindFrom(request.getHeader(GatewaySecurityConstants.HEADER_TENANT_ID));
        try {
            filterChain.doFilter(request, response);
        } finally {
            if (bound) {
                // Always: the thread goes back to the container's pool and must carry nothing.
                TenantContext.clear();
            }
        }
    }

    private static boolean bindFrom(String header) {
        if (header == null || header.isBlank()) {
            return false;
        }
        try {
            TenantContext.bind(UUID.fromString(header.trim()));
            return true;
        } catch (IllegalArgumentException e) {
            // Not a UUID. Bind nothing and let the database fail closed rather than guessing.
            log.warn("Ignoring malformed {} header", GatewaySecurityConstants.HEADER_TENANT_ID);
            return false;
        }
    }
}
