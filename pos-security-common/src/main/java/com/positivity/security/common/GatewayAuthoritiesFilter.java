package com.positivity.security.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Security filter that reads authentication headers injected by the API
 * gateway.
 *
 * <h2>How It Works</h2>
 * <ol>
 * <li>pos-api-gateway validates the JWT token against pos-security-service</li>
 * <li>Gateway injects {@code X-Authorities} and {@code X-User} headers</li>
 * <li>This filter reads those headers and populates
 * {@link SecurityContextHolder}</li>
 * <li>Controllers can use {@code @PreAuthorize} annotations normally</li>
 * </ol>
 *
 * <h2>⚠️ Security Assumption</h2>
 * <p>
 * This filter trusts headers from the gateway. It assumes:
 * </p>
 * <ul>
 * <li>Services are only accessible via the API gateway</li>
 * <li>External clients cannot directly reach services</li>
 * <li>Network isolation is enforced (Docker network, Kubernetes, etc.)</li>
 * </ul>
 * <p>
 * <b>If services are exposed externally, this filter alone is NOT secure.</b>
 * See {@link GatewaySecurityConstants} for hardening options.
 * </p>
 *
 * <p>
 * <b>Note:</b> This class is NOT annotated with @Component. It is created as a
 * bean
 * by {@link GatewaySecurityConfig} to avoid component scanning issues when the
 * library is imported via {@code @Import}.
 * </p>
 *
 * @see GatewaySecurityConstants
 * @see GatewaySecurityConfig
 */
@Slf4j
@Order(1)
public class GatewayAuthoritiesFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip authentication for actuator endpoints (health checks, metrics)
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        String authoritiesHeader = request.getHeader(GatewaySecurityConstants.HEADER_AUTHORITIES);
        String userHeader = request.getHeader(GatewaySecurityConstants.HEADER_USER);

        if (authoritiesHeader != null && !authoritiesHeader.isBlank()) {
            List<SimpleGrantedAuthority> authorities = parseAuthorities(authoritiesHeader);
            String username = userHeader != null ? userHeader : GatewaySecurityConstants.ANONYMOUS_USER;

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(username, null,
                    authorities);

            SecurityContextHolder.getContext().setAuthentication(authentication);

            if (log.isDebugEnabled()) {
                log.debug("Authenticated user '{}' with {} authorities from gateway headers",
                        username, authorities.size());
            }
        } else {
            // No authentication headers - clear any existing context
            SecurityContextHolder.clearContext();

            if (log.isTraceEnabled()) {
                log.trace("No gateway authentication headers for request: {} {}",
                        request.getMethod(), path);
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Parse comma-separated authorities string into Spring Security authorities.
     *
     * @param authoritiesHeader comma-separated authorities (e.g.,
     *                          "ROLE_ADMIN,crm:party:view")
     * @return list of granted authorities
     */
    private List<SimpleGrantedAuthority> parseAuthorities(String authoritiesHeader) {
        if (authoritiesHeader == null || authoritiesHeader.isBlank()) {
            return Collections.emptyList();
        }

        return Arrays.stream(authoritiesHeader.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(SimpleGrantedAuthority::new)
                .collect(Collectors.toList());
    }
}
