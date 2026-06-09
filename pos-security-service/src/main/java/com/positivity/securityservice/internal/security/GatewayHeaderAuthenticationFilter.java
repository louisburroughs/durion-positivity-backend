package com.positivity.securityservice.internal.security;

import com.positivity.securityservice.internal.domain.PermissionBitsetCodec;
import com.positivity.securityservice.internal.enums.PermissionCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Reads gateway-authentication headers and populates SecurityContext.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code X-Perm-Bits} + {@code X-Perm-Ver} — decoded via {@link PermissionBitsetCodec};
 *       requires {@code X-Perm-Ver} to match {@link PermissionCode#CATALOG_VERSION}.</li>
 *   <li>{@code X-Authorities} CSV — used when {@code X-Perm-Bits} is absent or decoding fails.</li>
 * </ol>
 * When neither source yields authorities the security context is left unauthenticated.
 */
public class GatewayHeaderAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER_AUTHORITIES = "X-Authorities";
    private static final String HEADER_PERM_BITS = "X-Perm-Bits";
    private static final String HEADER_PERM_VER = "X-Perm-Ver";
    private static final String HEADER_USER = "X-User";
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(GatewayHeaderAuthenticationFilter.class);

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String userHeader = request.getHeader(HEADER_USER);
        String username = userHeader != null && !userHeader.isBlank() ? userHeader : "gateway-user";

        List<SimpleGrantedAuthority> authorities = decodePermBits(request);
        if (authorities == null) {
            authorities = parseAuthorities(request.getHeader(HEADER_AUTHORITIES));
        }

        if (authorities != null && !authorities.isEmpty()) {
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            authentication.setDetails(Map.of("username", username));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            log.debug("Gateway auth established; user={} authorities={} uri={}",
                    username, authorities.size(), request.getRequestURI());
        } else {
            log.debug("No gateway auth headers; uri={}", request.getRequestURI());
        }

        filterChain.doFilter(request, response);
    }

    /**
     * Attempts to decode {@code X-Perm-Bits} using {@link PermissionBitsetCodec}.
     *
     * @return the decoded authorities list, or {@code null} if the header is absent,
     *         the version is missing/invalid, or decoding fails
     */
    private List<SimpleGrantedAuthority> decodePermBits(HttpServletRequest request) {
        String permBitsHeader = request.getHeader(HEADER_PERM_BITS);
        if (permBitsHeader == null || permBitsHeader.isBlank()) {
            return null;
        }

        String permVerHeader = request.getHeader(HEADER_PERM_VER);
        int permVer;
        try {
            permVer = Integer.parseInt(permVerHeader);
        } catch (NumberFormatException | NullPointerException e) {
            log.warn("X-Perm-Bits present but X-Perm-Ver is missing or invalid (value={}); falling back to X-Authorities uri={}",
                    permVerHeader, request.getRequestURI());
            return null;
        }

        if (permVer != PermissionCode.CATALOG_VERSION) {
            log.warn("X-Perm-Ver {} does not match local catalog version {}; falling back to X-Authorities uri={}",
                    permVer, PermissionCode.CATALOG_VERSION, request.getRequestURI());
            return null;
        }

        try {
            Set<PermissionCode> permissions = PermissionBitsetCodec.decodeToPermissions(permBitsHeader, permVer);
            return permissions.stream()
                    .map(p -> new SimpleGrantedAuthority(p.code()))
                    .toList();
        } catch (Exception e) {
            log.warn("Failed to decode X-Perm-Bits; falling back to X-Authorities uri={} error={}",
                    request.getRequestURI(), e.getMessage());
            return null;
        }
    }

    /**
     * Parses a comma-separated {@code X-Authorities} header value into granted authorities.
     *
     * @return the authority list, or {@code null} if the header is absent or blank
     */
    private List<SimpleGrantedAuthority> parseAuthorities(String authoritiesHeader) {
        if (authoritiesHeader == null || authoritiesHeader.isBlank()) {
            return null;
        }
        return Arrays.stream(authoritiesHeader.split(","))
                .map(String::trim)
                .filter(authority -> !authority.isEmpty())
                .map(SimpleGrantedAuthority::new)
                .toList();
    }
}
