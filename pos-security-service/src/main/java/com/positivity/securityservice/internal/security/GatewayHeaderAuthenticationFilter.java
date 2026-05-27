package com.positivity.securityservice.internal.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads gateway-authentication headers and populates SecurityContext.
 */
public class GatewayHeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_AUTHORITIES = "X-Authorities";
    private static final String HEADER_USER = "X-User";
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GatewayHeaderAuthenticationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String authoritiesHeader = request.getHeader(HEADER_AUTHORITIES);
        String userHeader = request.getHeader(HEADER_USER);

        if (authoritiesHeader != null && !authoritiesHeader.isBlank()) {
            List<SimpleGrantedAuthority> authorities = parseAuthorities(authoritiesHeader);
            String username = userHeader != null && !userHeader.isBlank() ? userHeader : "gateway-user";

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            authentication.setDetails(Map.of("username", username));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Gateway auth established; user={} authorities={} uri={}",
                    username, authorities.size(), request.getRequestURI());
        } else {
            log.debug("No X-Authorities header; uri={}", request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    private List<SimpleGrantedAuthority> parseAuthorities(String authoritiesHeader) {
        if (authoritiesHeader == null || authoritiesHeader.isBlank()) {
            return Collections.emptyList();
        }
        return Arrays.stream(authoritiesHeader.split(","))
                .map(String::trim)
                .filter(authority -> !authority.isEmpty())
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
