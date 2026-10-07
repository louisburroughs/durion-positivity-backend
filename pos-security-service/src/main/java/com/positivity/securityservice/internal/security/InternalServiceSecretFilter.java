package com.positivity.securityservice.internal.security;

import com.positivity.security.common.SecurityApiConstants;
import com.positivity.shared.error.ApiError;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Authenticates a caller of pos-security-service's {@code /internal/**} surface by the mesh service
 * credential (CAP:550 S16, #2512): the shared secret every service holds as {@code
 * pos.security.api-secret} ({@code POS_SECURITY_API_SECRET}), presented on {@value
 * SecurityApiConstants#INTERNAL_SECRET_HEADER} and compared in constant time. Gateway identity
 * headers are not accepted here, so a forwarded user cannot reach the surface, and the gateway refuses
 * {@code /internal} paths at the edge in any case.
 *
 * <p>A missing secret configuration or a wrong or absent header answers 401 before the controller; a
 * match authenticates the request as the internal service, {@code ROLE_INTERNAL_SERVICE}, named after
 * the {@code X-User} the caller relays for the audit log. Instantiated by {@code SecurityConfig} for
 * its internal chain only, never registered as a servlet filter of its own.
 */
public class InternalServiceSecretFilter extends OncePerRequestFilter {

    /** The authority an authenticated internal caller carries. */
    public static final String INTERNAL_SERVICE_ROLE = "ROLE_INTERNAL_SERVICE";

    private static final String RELAYED_USER_HEADER = "X-User";

    private final String expectedSecret;
    private final Clock clock;
    private final ObjectMapper objectMapper;

    public InternalServiceSecretFilter(String expectedSecret, Clock clock, ObjectMapper objectMapper) {
        this.expectedSecret = expectedSecret;
        this.clock = clock;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        if (!SecurityApiConstants.hasSecret(expectedSecret)) {
            writeUnauthorized(
                    response, request, "INTERNAL_SECRET_MISSING", "Internal service credential is not configured");
            return;
        }
        String provided = request.getHeader(SecurityApiConstants.INTERNAL_SECRET_HEADER);
        boolean authorized = provided != null
                && MessageDigest.isEqual(
                        expectedSecret.getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
        if (!authorized) {
            writeUnauthorized(
                    response, request, "INVALID_INTERNAL_SECRET", "Invalid or missing internal service credential");
            return;
        }
        String relayed = request.getHeader(RELAYED_USER_HEADER);
        String principal = "internal-service:" + (relayed == null || relayed.isBlank() ? "unknown" : relayed);
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated(
                principal, null, List.of(new SimpleGrantedAuthority(INTERNAL_SERVICE_ROLE)));
        authentication.setDetails(Map.of("username", principal));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        filterChain.doFilter(request, response);
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
        response.setHeader("X-Correlation-Id", correlationId);
        response.setContentType("application/json;charset=UTF-8");
        objectMapper.writeValue(response.getWriter(), body);
    }
}
