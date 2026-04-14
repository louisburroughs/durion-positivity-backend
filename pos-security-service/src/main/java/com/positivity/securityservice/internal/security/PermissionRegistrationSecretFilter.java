package com.positivity.securityservice.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.shared.error.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Allows internal services to register permissions using a shared secret.
 */
@Component
@Order(0)
public class PermissionRegistrationSecretFilter extends OncePerRequestFilter {

    private static final String SECRET_HEADER = "X-Permissions-Api-Secret";
    private static final String REGISTER_PATH = "/v1/permissions/register";
    private static final String REGISTER_ALIAS_PATH = "/v1/users/permissions/register";
    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS");
    private static final String REQUIRED_AUTHORITY = "security:permission:register";

    private final String expectedSecret;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public PermissionRegistrationSecretFilter(
            @Value("${pos.security.api-secret:}") String expectedSecret, Clock clock, ObjectMapper objectMapper) {
        this.expectedSecret = expectedSecret;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();
        if (!isProtectedPath(path) || SAFE_METHODS.contains(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!HttpMethod.POST.matches(method)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (expectedSecret == null || expectedSecret.isBlank()) {
            writeUnauthorized(
                    response,
                    request,
                    "PERMISSION_REGISTRATION_SECRET_MISSING",
                    "Permission registration secret is not configured");
            return;
        }

        String providedSecret = request.getHeader(SECRET_HEADER);
        if (providedSecret == null || !expectedSecret.equals(providedSecret)) {
            writeUnauthorized(
                    response,
                    request,
                    "INVALID_PERMISSION_REGISTRATION_SECRET",
                    "Invalid or missing permission registration secret");
            return;
        }

        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                "permission-registration-service", null, List.of(new SimpleGrantedAuthority(REQUIRED_AUTHORITY)));
        authentication.setDetails(Map.of("username", "permission-registration-service"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private boolean isProtectedPath(String path) {
        return REGISTER_PATH.equals(path) || REGISTER_ALIAS_PATH.equals(path);
    }

    private void writeUnauthorized(
            HttpServletResponse response, HttpServletRequest request, String code, String message) throws IOException {
        String correlationId = request.getHeader("X-Correlation-Id");
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        ApiError body = new ApiError(
                code,
                message,
                HttpServletResponse.SC_UNAUTHORIZED,
                Instant.now(clock).toString(),
                correlationId,
                null,
                null,
                null,
                null);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
