package com.positivity.security.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

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
@Order(1)
public class GatewayAuthoritiesFilter extends OncePerRequestFilter {
    private static final Logger loggr = LoggerFactory.getLogger(GatewayAuthoritiesFilter.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String path = request.getRequestURI();

        // Skip authentication for actuator endpoints (health checks, metrics)
        if (path.startsWith("/actuator")) {
            filterChain.doFilter(request, response);
            return;
        }

        String permBitsHeader = request.getHeader(GatewaySecurityConstants.HEADER_PERM_BITS);
        String permVerHeader = request.getHeader(GatewaySecurityConstants.HEADER_PERM_VER);
        String authoritiesHeader = request.getHeader(GatewaySecurityConstants.HEADER_AUTHORITIES);
        String rolesHeader = request.getHeader(GatewaySecurityConstants.HEADER_ROLES);
        String userHeader = request.getHeader(GatewaySecurityConstants.HEADER_USER);
        String authorizationHeader = request.getHeader(AUTHORIZATION_HEADER);

        boolean hasPermBits = StringUtils.hasText(permBitsHeader);

        if (hasPermBits || StringUtils.hasText(authoritiesHeader) || StringUtils.hasText(rolesHeader)) {
            List<SimpleGrantedAuthority> authorities = hasPermBits
                    ? authoritiesFromPermBits(permBitsHeader, permVerHeader, rolesHeader)
                    : parseAuthorities(authoritiesHeader, rolesHeader);

            if (authorities == null) {
                // authoritiesFromPermBits returned null — invalid/untrusted perm-bits headers; fail closed
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            String username = userHeader != null ? userHeader : GatewaySecurityConstants.ANONYMOUS_USER;
            Optional<UUID> userId = resolveUserIdFromToken(authorizationHeader, username);

            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(username, null,
                    authorities);
            Map<String, Object> details = new HashMap<>();
            details.put(GatewaySecurityConstants.DETAIL_USERNAME, username);
            userId.ifPresent(id -> details.put(GatewaySecurityConstants.DETAIL_USER_ID, id));
            authentication.setDetails(Map.copyOf(details));

            SecurityContextHolder.getContext().setAuthentication(authentication);

            if (loggr.isDebugEnabled()) {
                loggr.debug(
                        "Authenticated user '{}' (userId='{}') with {} authorities and {} role authorities from gateway headers",
                        username,
                        userId.orElse(null),
                        authorities.size(),
                        authorities.stream()
                                .map(SimpleGrantedAuthority::getAuthority)
                                .filter(authority -> authority.startsWith(GatewaySecurityConstants.ROLE_PREFIX))
                                .count());
            }
        } else {
            // No authentication headers - clear any existing context
            SecurityContextHolder.clearContext();

            if (loggr.isTraceEnabled()) {
                loggr.trace("No gateway authentication headers for request: {} {}", request.getMethod(), path);
            }
        }

        filterChain.doFilter(request, response);
    }

    private Optional<UUID> resolveUserIdFromToken(String authorizationHeader, String username) {
        if (authorizationHeader == null
                || authorizationHeader.isBlank()
                || !authorizationHeader.startsWith(BEARER_PREFIX)) {
            loggr.warn("Missing bearer token while resolving userId for user '{}'", username);
            return Optional.empty();
        }

        String token = authorizationHeader.substring(BEARER_PREFIX.length()).trim();
        if (token.isBlank()) {
            loggr.warn("Blank bearer token while resolving userId for user '{}'", username);
            return Optional.empty();
        }

        try {
            String[] jwtParts = token.split("\\.");
            if (jwtParts.length < 2) {
                loggr.warn("Invalid JWT format while resolving userId for user '{}'", username);
                return Optional.empty();
            }

            byte[] payloadBytes = Base64.getUrlDecoder().decode(jwtParts[1]);
            JsonNode payloadNode = objectMapper.readTree(payloadBytes);
            JsonNode userIdNode = firstNonBlankClaim(
                    payloadNode, GatewaySecurityConstants.CLAIM_UID, GatewaySecurityConstants.CLAIM_USER_ID_LEGACY);
            if (userIdNode == null || userIdNode.asText().isBlank()) {
                loggr.warn(
                        "Missing JWT '{}' and legacy '{}' claims for user '{}'",
                        GatewaySecurityConstants.CLAIM_UID,
                        GatewaySecurityConstants.CLAIM_USER_ID_LEGACY,
                        username);
                return Optional.empty();
            }

            return Optional.of(UUID.fromString(userIdNode.asText()));
        } catch (Exception ex) {
            loggr.warn("Failed to resolve userId from JWT token for user '{}': {}", username, ex.getMessage());
            return Optional.empty();
        }
    }

    private JsonNode firstNonBlankClaim(JsonNode payloadNode, String... claimNames) {
        for (String claimName : claimNames) {
            JsonNode claimNode = payloadNode.get(claimName);
            if (claimNode != null && !claimNode.asText().isBlank()) {
                return claimNode;
            }
        }
        return null;
    }

    /**
     * Parse comma-separated authorities string into Spring Security authorities.
     *
     * @param authoritiesHeader comma-separated authorities (e.g.,
     *                          "ROLE_ADMIN,crm:party:view")
     * @return list of granted authorities
     */
    private List<SimpleGrantedAuthority> parseAuthorities(String authoritiesHeader, String rolesHeader) {
        if (!StringUtils.hasText(authoritiesHeader) && !StringUtils.hasText(rolesHeader)) {
            return Collections.emptyList();
        }

        Stream<String> authorityStream = csvValues(authoritiesHeader).flatMap(this::expandAuthority);
        Stream<String> roleStream = csvValues(rolesHeader);

        return Stream.concat(authorityStream, roleStream)
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    // null return = decode failure (fail closed); empty list = valid header with zero permissions.
    @SuppressWarnings("java:S1168")
    private List<SimpleGrantedAuthority> authoritiesFromPermBits(
            String permBitsHeader, String permVerHeader, String rolesHeader) {
        if (permVerHeader == null) {
            loggr.warn("Missing X-Perm-Ver header; clearing auth context");
            return null;
        }
        int permVer;
        try {
            permVer = Integer.parseInt(permVerHeader);
        } catch (NumberFormatException _) {
            loggr.warn("Invalid X-Perm-Ver header '{}'; clearing auth context", permVerHeader);
            return null;
        }

        if (permVer != DownstreamPermissionCatalog.CATALOG_VERSION) {
            loggr.warn("X-Perm-Ver {} does not match local catalog version {}; clearing auth context",
                    permVer, DownstreamPermissionCatalog.CATALOG_VERSION);
            return null;
        }

        BitSet bits;
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(permBitsHeader);
            bits = BitSet.valueOf(bytes);
        } catch (IllegalArgumentException e) {
            loggr.warn("Malformed X-Perm-Bits header: {}; clearing auth context", e.getMessage());
            return null;
        }

        Stream<String> permStream = DownstreamPermissionCatalog.authoritiesFromBitSet(bits)
                .stream()
                .flatMap(this::expandAuthority);
        Stream<String> roleStream = csvValues(rolesHeader);

        return Stream.concat(permStream, roleStream)
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    private Stream<String> csvValues(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return Stream.empty();
        }
        return Arrays.stream(headerValue.split(",")).map(String::trim).filter(s -> !s.isEmpty());
    }

    private Stream<String> expandAuthority(String authority) {
        if (!authority.startsWith(GatewaySecurityConstants.PERMISSION_PREFIX)) {
            return Stream.of(authority);
        }

        String plainPermission = authority.substring(GatewaySecurityConstants.PERMISSION_PREFIX.length());
        if (plainPermission.isBlank()) {
            return Stream.of(authority);
        }

        // Preserve the raw gateway authority and the canonical plain permission
        // string expected by existing @PreAuthorize checks in downstream services.
        return new LinkedHashSet<>(List.of(authority, plainPermission)).stream();
    }
}
