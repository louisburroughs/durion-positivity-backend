package com.positivity.poseventreceiver.internal.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Security filter for the Events API that validates requests using a shared
 * secret.
 *
 * <h2>Security Model</h2>
 * <p>
 * This filter implements a shared-secret authentication model to secure the
 * Events API
 * without requiring JWT tokens from pos-security-service. This avoids circular
 * dependencies
 * during service startup while still providing authentication for write
 * operations.
 * </p>
 *
 * <h2>Protected Endpoints</h2>
 * <ul>
 * <li>POST, PUT, DELETE on /v1/events/* - Event emission endpoints</li>
 * <li>POST, PUT, DELETE on /v1/eventTypes/* - Event type registration
 * endpoints</li>
 * </ul>
 *
 * <h2>Unprotected Endpoints</h2>
 * <ul>
 * <li>GET requests - Read operations are allowed without authentication</li>
 * <li>/actuator/* - Health checks and metrics</li>
 * </ul>
 *
 * <h2>Configuration</h2>
 * <p>
 * Set the shared secret via environment variable or application property:
 * {@code POS_EVENTS_API_SECRET} or {@code pos.events.api-secret}
 * </p>
 */
@Slf4j
@Component
@Order(1)
public class EventsApiSecurityFilter extends OncePerRequestFilter {

    private static final String SECRET_HEADER = "X-Events-Api-Secret";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");

    private final String expectedSecret;
    private final boolean securityEnabled;

    public EventsApiSecurityFilter(
            @Value("${pos.events.api-secret:}") String expectedSecret) {
        this.expectedSecret = expectedSecret;
        this.securityEnabled = expectedSecret != null && !expectedSecret.isBlank();

        if (!securityEnabled) {
            log.warn("⚠️ Events API security is DISABLED - no secret configured. "
                    + "Set POS_EVENTS_API_SECRET environment variable to enable.");
        } else {
            log.info("Events API security enabled with shared secret authentication");
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        // Skip security for actuator endpoints
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        // Skip security for safe (read-only) methods
        if (SAFE_METHODS.contains(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Only protect /v1/events and /v1/eventTypes endpoints
        if (!path.startsWith("/v1/events") && !path.startsWith("/v1/eventTypes")) {
            filterChain.doFilter(request, response);
            return;
        }

        // If security is disabled, allow all requests (dev mode)
        if (!securityEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        // Validate the shared secret
        String providedSecret = request.getHeader(SECRET_HEADER);
        if (providedSecret == null || !expectedSecret.equals(providedSecret)) {
            log.warn("Unauthorized request to {} {} - invalid or missing {} header",
                    method, path, SECRET_HEADER);
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"Unauthorized\",\"message\":\"Invalid or missing API secret\"}");
            return;
        }

        filterChain.doFilter(request, response);
    }
}
