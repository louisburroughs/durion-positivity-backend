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
 * authentication an earlier filter established, so a request that presents a broken bearer
 * token can never fall back to {@link GatewayHeaderAuthenticationFilter}'s header-derived
 * authorities. The downstream authorization filter then rejects it and
 * {@code JsonAuthenticationEntryPoint} renders the enveloped, correlated 401. This mirrors the
 * fail-closed contract {@link GatewayHeaderAuthenticationFilter} already documents for
 * {@code X-Perm-Bits}.
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
                authenticate(request, token);
            } catch (SecurityValidationException
                    | JwtException
                    | IllegalArgumentException
                    | AuthenticationException e) {
                // SecurityValidationException: malformed perm_bits or an unsupported perm_ver.
                // JwtException / IllegalArgumentException: a claim re-parse that validateToken
                // accepted but a later read rejects. AuthenticationException: the subject no
                // longer resolves to a user (UsernameNotFoundException).
                SecurityContextHolder.clearContext();
                log.warn(
                        "Bearer token rejected; clearing auth context uri={} error={} reason={}",
                        request.getRequestURI(),
                        e.getClass().getSimpleName(),
                        e.getMessage());
            }
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(HttpServletRequest request, String token) {
        if (!jwtService.validateToken(token)) {
            return;
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
    }
}
