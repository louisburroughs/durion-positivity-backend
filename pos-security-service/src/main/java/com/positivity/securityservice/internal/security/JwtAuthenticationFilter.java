package com.positivity.securityservice.internal.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.security.service.JwtService;
import com.positivity.shared.error.ApiError;
import com.positivity.shared.id.UUIDv7Generator;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Establishes the security context from a {@code Bearer} access token.
 *
 * <p><strong>Nothing may leave this filter unenveloped.</strong> The filter runs before Spring
 * Security's {@code ExceptionTranslationFilter} (it is registered with
 * {@code addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)}), and a servlet filter
 * runs before the dispatcher, so nothing thrown here can be seen by this module's
 * {@code @ControllerAdvice} or by {@code pos-web-common}'s {@code GlobalApiExceptionHandler}: the
 * container would render its own default page instead of the {@code ApiError} envelope, defeating
 * ADR-0056 §1 and ADR-0017 §3/§4. The instance #1715 reported was a token whose {@code perm_bits}
 * claim no longer decodes — a corrupt claim, or one written against an older permission catalog
 * version — which made {@code PermissionBitsetCodec} throw out of
 * {@link JwtService#getAuthoritiesFromToken}.
 *
 * <p>Two distinct outcomes keep that guarantee, because a credential problem and a server fault
 * must not answer alike:
 *
 * <ul>
 *   <li><strong>The credential is bad → 401.</strong> A token that throws while being read
 *       ({@link SecurityValidationException}, {@link JwtException}, {@code IllegalArgumentException},
 *       {@link AuthenticationException}) or that {@link JwtService#validateToken} refuses leaves the
 *       request unauthenticated and clears any authentication an earlier filter established. The
 *       downstream authorization filter rejects it and {@code JsonAuthenticationEntryPoint} renders
 *       the enveloped, correlated 401.
 *   <li><strong>The server failed → enveloped 500.</strong> Any other {@code RuntimeException} —
 *       {@code RedisConnectionFailureException} from the revocation check, a
 *       {@code DataAccessException} from the token store or the user lookup, a {@code NullPointerException}
 *       on a token with no {@code exp} claim — is a server fault, not a bad credential. Answering
 *       401 would tell the caller to fix a token that is fine, so the filter writes the {@code ApiError}
 *       envelope itself (the pattern {@link PermissionRegistrationSecretFilter} already uses in this
 *       package) and logs the stack trace at ERROR against the same correlation id.
 * </ul>
 *
 * <p><strong>A refused token is treated the same as a failed one.</strong> When a bearer token is
 * present but {@link JwtService#validateToken} refuses it, the context is cleared too. This matters
 * because the gateway validates only signature, issuer, audience and expiry — it does not consult
 * revocation — so a revoked or logged-out token still reaches this service carrying gateway-derived
 * {@code X-Perm-Bits}. Returning without clearing let those header authorities carry the request,
 * which meant revocation and logout did not take effect at the very service that owns them.
 *
 * <p><strong>Scope.</strong> {@code SecurityConfig} places this filter on the {@code /v1/auth/**}
 * chain only. Because it is also a {@code Filter} bean, Spring Boot would otherwise register it
 * with the servlet container at {@code /*} as well, where it runs <em>after</em>
 * {@code springSecurityFilterChain} — clearing the context after authorization has already passed
 * and leaving a null principal for the dispatcher. {@code SecurityBeansConfig} disables that
 * container registration; see the {@code FilterRegistrationBean} there.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    /**
     * Request attribute carrying the correlation id this filter resolved, so that the 401
     * {@code JsonAuthenticationEntryPoint} eventually renders reports the same id this filter
     * logged the reason against. Without it the two are unjoinable: the entry point mints its own
     * id when the client sent none, and its body says only {@code INVALID_CREDENTIALS} — the
     * reason (stale {@code perm_ver}, decode failure, deleted user, revoked token) lives solely in
     * this filter's log line. ADR-0017 §4 makes the correlation id the diagnostic handle; this is
     * what makes it one.
     */
    public static final String CORRELATION_ID_ATTRIBUTE = "com.positivity.security.correlationId";

    private static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = null;
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            token = header.substring(7);
        }
        if (token != null) {
            String correlationId = resolveCorrelationId(request);
            try {
                if (!authenticate(request, token)) {
                    // validateToken said no: expired, revoked, deleted by logout, or absent from
                    // the token store. Logged at DEBUG, not WARN — an expired access token
                    // arriving alongside a refresh call is ordinary traffic, and validateToken
                    // already logs the specific reason at DEBUG.
                    SecurityContextHolder.clearContext();
                    log.debug(
                            "Access-token authentication refused; clearing auth context uri={} correlationId={}",
                            request.getRequestURI(),
                            correlationId);
                }
            } catch (SecurityValidationException
                    | JwtException
                    | IllegalArgumentException
                    | AuthenticationException e) {
                // The credential is bad: malformed perm_bits, an unsupported perm_ver, a claim
                // re-parse that validateToken accepted but a later read rejects, or a subject that
                // no longer resolves to a user. Fail closed and let the entry point answer 401.
                SecurityContextHolder.clearContext();
                log.warn(
                        "Access-token authentication rejected; clearing auth context uri={} correlationId={} error={} reason={}",
                        request.getRequestURI(),
                        correlationId,
                        e.getClass().getSimpleName(),
                        e.getMessage());
            } catch (RuntimeException e) {
                // The server failed, not the credential. Answering 401 would misdirect the caller
                // into replacing a perfectly good token, so this answers an enveloped, correlated
                // 500 — the shape ADR-0056 §1 requires of a catch-all — instead of the container's
                // default page.
                SecurityContextHolder.clearContext();
                log.error(
                        "Access-token authentication failed unexpectedly; uri={} correlationId={}",
                        request.getRequestURI(),
                        correlationId,
                        e);
                writeInternalError(response, correlationId);
                return;
            }
        }
        filterChain.doFilter(request, response);
    }

    /**
     * @return {@code true} when the token authenticated the request; {@code false} when
     *         {@link JwtService#validateToken} refused it, which the caller answers by failing
     *         closed
     */
    private boolean authenticate(HttpServletRequest request, String token) {
        if (!jwtService.validateToken(token)) {
            return false;
        }
        String username = jwtService.getUsernameFromToken(token);
        UserDetails userDetails = userDetailsService.loadUserByUsername(username);
        Set<String> roles = jwtService.getRolesFromToken(token);
        Set<String> authorities = jwtService.getAuthoritiesFromToken(token);
        Set<GrantedAuthority> granted = Stream.concat(
                        roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)),
                        authorities.stream().map(SimpleGrantedAuthority::new))
                .collect(Collectors.toSet());

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(userDetails, null, granted);
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return true;
    }

    /**
     * Resolves the correlation id once and publishes it on the request, so the entry point that
     * later renders the 401 reports the same id this filter logged against (ADR-0017 §4).
     */
    private String resolveCorrelationId(HttpServletRequest request) {
        Object existing = request.getAttribute(CORRELATION_ID_ATTRIBUTE);
        if (existing instanceof String s && !s.isBlank()) {
            return s;
        }
        String correlationId = request.getHeader(CORRELATION_ID_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUIDv7Generator.generate().toString();
        }
        request.setAttribute(CORRELATION_ID_ATTRIBUTE, correlationId);
        return correlationId;
    }

    private void writeInternalError(HttpServletResponse response, String correlationId) throws IOException {
        ApiError body = new ApiError(
                "INTERNAL_ERROR",
                "Unexpected error occurred",
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                Instant.now(clock).toString(),
                correlationId,
                null,
                null,
                null,
                null);
        response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(CORRELATION_ID_HEADER, correlationId);
        objectMapper.writeValue(response.getWriter(), body);
    }
}
