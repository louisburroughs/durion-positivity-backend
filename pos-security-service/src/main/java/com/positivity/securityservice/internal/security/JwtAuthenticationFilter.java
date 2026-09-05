package com.positivity.securityservice.internal.security;

import com.positivity.securityservice.internal.exception.SecurityValidationException;
import com.positivity.securityservice.internal.security.service.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
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
 * <p><strong>Fail closed on any token-processing failure.</strong> This filter runs before
 * Spring Security's {@code ExceptionTranslationFilter} (it is registered with
 * {@code addFilterBefore(..., UsernamePasswordAuthenticationFilter.class)}), and a servlet
 * filter runs before the dispatcher, so nothing thrown here can be seen by this module's
 * {@code @ControllerAdvice} or by {@code pos-web-common}'s {@code GlobalApiExceptionHandler}:
 * the container would render its own default page instead of the {@code ApiError} envelope,
 * defeating ADR-0056 §1 and ADR-0017 §3/§4. The reachable instance was a token whose
 * {@code perm_bits} claim no longer decodes — a corrupt claim, or one written against an older
 * permission catalog version — which made {@code PermissionBitsetCodec} throw out of
 * {@link JwtService#getAuthoritiesFromToken} (issue #1715).
 *
 * <p>Instead of propagating, a failure leaves the request unauthenticated and clears any
 * authentication an earlier filter established. The downstream authorization filter then
 * rejects it and {@code JsonAuthenticationEntryPoint} renders the enveloped, correlated 401.
 *
 * <p><strong>A refused token is treated the same as a failed one.</strong> When a bearer token
 * is present but {@link JwtService#validateToken} refuses it, the context is cleared too. This
 * matters because the gateway validates only signature, issuer, audience and expiry — it does
 * not consult revocation — so a revoked or logged-out token still reaches this service carrying
 * gateway-derived {@code X-Perm-Bits}. Returning without clearing let those header authorities
 * carry the request, which meant revocation and logout did not take effect at the very service
 * that owns them. Presenting a credential this service refuses now always denies the request
 * rather than silently ignoring the credential and falling back to
 * {@link GatewayHeaderAuthenticationFilter}'s headers.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        String token = null;
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            token = header.substring(7);
        }
        if (token != null) {
            try {
                if (!authenticate(request, token)) {
                    // validateToken said no: expired, revoked, deleted by logout, or absent from
                    // the token store. Logged at DEBUG, not WARN — an expired access token
                    // arriving alongside a refresh call is ordinary traffic, and validateToken
                    // already logs the specific reason at DEBUG.
                    SecurityContextHolder.clearContext();
                    log.debug(
                            "Access-token authentication refused; clearing auth context uri={}",
                            request.getRequestURI());
                }
            } catch (SecurityValidationException
                    | JwtException
                    | IllegalArgumentException
                    | AuthenticationException e) {
                // SecurityValidationException: malformed perm_bits or an unsupported perm_ver.
                // JwtException / IllegalArgumentException: a claim re-parse that validateToken
                // accepted but a later read rejects. AuthenticationException: the subject no
                // longer resolves to a user (UsernameNotFoundException). Anomalous, so WARN.
                SecurityContextHolder.clearContext();
                log.warn(
                        "Access-token authentication rejected; clearing auth context uri={} error={} reason={}",
                        request.getRequestURI(),
                        e.getClass().getSimpleName(),
                        e.getMessage());
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
}
