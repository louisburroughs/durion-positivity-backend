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
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
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
    /** The only {@code loc_scope} shape this filter reads; anything else is treated as absent. */
    private static final int LOC_SCOPE_VERSION = 1;

    private final @Nullable LocationAncestorResolver locationAncestorResolver;

    /** A filter for a module that provides no {@link LocationAncestorResolver}: scoped permissions deny. */
    public GatewayAuthoritiesFilter() {
        this(null);
    }

    /**
     * @param locationAncestorResolver the module's ancestor-set resolver, or {@code null} when the
     *     module provides none (every location-scoped permission is then denied, ADR-0061 §3)
     */
    public GatewayAuthoritiesFilter(@Nullable LocationAncestorResolver locationAncestorResolver) {
        this.locationAncestorResolver = locationAncestorResolver;
    }

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

            // The scope bitsets share perm_bits' indexes and X-Perm-Ver, which the perm-bits path
            // above has already validated; a legacy X-Authorities request predates the claims.
            LocationScope locationScope = hasPermBits ? locationScopeFromHeaders(request) : LocationScope.unscoped();
            if (locationScope == null) {
                // A scope bitset that does not decode is as untrusted as a perm bitset that does not.
                SecurityContextHolder.clearContext();
                filterChain.doFilter(request, response);
                return;
            }

            String username = userHeader != null ? userHeader : GatewaySecurityConstants.ANONYMOUS_USER;
            Optional<UUID> userId = resolveUserIdFromToken(authorizationHeader, username);

            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(username, null, authorities);
            Map<String, Object> details = new HashMap<>();
            details.put(GatewaySecurityConstants.DETAIL_USERNAME, username);
            userId.ifPresent(id -> details.put(GatewaySecurityConstants.DETAIL_USER_ID, id));
            details.put(GatewaySecurityConstants.DETAIL_LOCATION_SCOPE, locationScope);
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
            loggr.warn(
                    "X-Perm-Ver {} does not match local catalog version {}; clearing auth context",
                    permVer,
                    DownstreamPermissionCatalog.CATALOG_VERSION);
            return null;
        }

        BitSet bits;
        try {
            bits = decodeBitSet(permBitsHeader);
        } catch (IllegalArgumentException e) {
            loggr.warn("Malformed X-Perm-Bits header: {}; clearing auth context", e.getMessage());
            return null;
        }

        Stream<String> permStream =
                DownstreamPermissionCatalog.authoritiesFromBitSet(bits).stream().flatMap(this::expandAuthority);
        Stream<String> roleStream = csvValues(rolesHeader);

        return Stream.concat(permStream, roleStream)
                .distinct()
                .map(SimpleGrantedAuthority::new)
                .toList();
    }

    /** The one Base64URL-to-{@link BitSet} path, shared by {@code X-Perm-Bits} and the scope bitsets. */
    private static BitSet decodeBitSet(String base64Url) {
        return BitSet.valueOf(Base64.getUrlDecoder().decode(base64Url));
    }

    /**
     * Builds the caller's {@link LocationScope} from the three {@code X-Loc-*} headers (#1870).
     *
     * <p>Both bitsets absent means the token predates the claims: the scope is
     * {@link LocationScope#unscoped()}. A bitset that fails to decode returns {@code null} so the
     * caller fails closed, exactly as for {@code X-Perm-Bits}. A missing or malformed
     * {@code X-Loc-Scope} is treated as absent, which denies every scoped permission — the
     * fail-closed direction, and the shape the issuer deliberately sends for a caller with no
     * assigned node.
     */
    // null return = decode failure (fail closed), mirroring authoritiesFromPermBits.
    @SuppressWarnings("java:S1168")
    private @Nullable LocationScope locationScopeFromHeaders(HttpServletRequest request) {
        String finHeader = request.getHeader(GatewaySecurityConstants.HEADER_LOC_FIN_BITS);
        String othHeader = request.getHeader(GatewaySecurityConstants.HEADER_LOC_OTH_BITS);
        if (finHeader == null && othHeader == null) {
            return LocationScope.unscoped();
        }

        Set<String> financialScoped;
        Set<String> otherScoped;
        try {
            financialScoped = scopedPermissions(finHeader);
            otherScoped = scopedPermissions(othHeader);
        } catch (IllegalArgumentException e) {
            loggr.warn("Malformed location-scope bitset header: {}; clearing auth context", e.getMessage());
            return null;
        }

        Optional<Set<UUID>> nodes =
                decodeLocationScopeHeader(request.getHeader(GatewaySecurityConstants.HEADER_LOC_SCOPE));
        return LocationScope.of(financialScoped, otherScoped, nodes, true, locationAncestorResolver);
    }

    /** Plain permission names for the set bits; an absent or empty header is an empty set. */
    private static Set<String> scopedPermissions(@Nullable String bitsHeader) {
        if (!StringUtils.hasText(bitsHeader)) {
            return Set.of();
        }
        Set<String> permissions = new LinkedHashSet<>();
        for (String authority : DownstreamPermissionCatalog.authoritiesFromBitSet(decodeBitSet(bitsHeader))) {
            permissions.add(
                    authority.startsWith(GatewaySecurityConstants.PERMISSION_PREFIX)
                            ? authority.substring(GatewaySecurityConstants.PERMISSION_PREFIX.length())
                            : authority);
        }
        return permissions;
    }

    /**
     * Decodes {@code X-Loc-Scope}: Base64URL of {@code {"v":1,"nodes":["<uuid>",...]}}. Anything
     * that does not match — wrong version, missing or non-array {@code nodes}, a node that is not
     * a UUID, bad Base64, bad JSON — is logged and treated as absent.
     */
    private static Optional<Set<UUID>> decodeLocationScopeHeader(@Nullable String header) {
        if (header == null) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(Base64.getUrlDecoder().decode(header));
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException("loc_scope is not a JSON object");
            }
            JsonNode version = root.get("v");
            if (version == null || !version.isInt() || version.intValue() != LOC_SCOPE_VERSION) {
                throw new IllegalArgumentException("unsupported loc_scope version " + version);
            }
            JsonNode nodesNode = root.get("nodes");
            if (nodesNode == null || !nodesNode.isArray()) {
                throw new IllegalArgumentException("loc_scope nodes is not an array");
            }
            Set<UUID> nodes = new LinkedHashSet<>();
            for (JsonNode node : nodesNode) {
                if (!node.isTextual()) {
                    throw new IllegalArgumentException("loc_scope node is not a string");
                }
                nodes.add(UUID.fromString(node.asText()));
            }
            return Optional.of(nodes);
        } catch (IOException | IllegalArgumentException e) {
            loggr.warn(
                    "Malformed {} header treated as absent (scoped permissions will be denied): {}",
                    GatewaySecurityConstants.HEADER_LOC_SCOPE,
                    e.getMessage());
            return Optional.empty();
        }
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
