package com.positivity.security.common;

/**
 * Constants for gateway-based security headers and configuration.
 *
 * <h2>Security Model</h2>
 * <p>
 * The pos-api-gateway validates JWT tokens and injects security headers
 * into downstream service requests. Services trust these headers because:
 * </p>
 * <ul>
 *   <li><b>Network isolation:</b> Services are only accessible via the gateway
 *       within the Docker network (pos-network). External clients cannot directly
 *       access services to inject fake headers.</li>
 *   <li><b>Gateway validation:</b> The gateway calls pos-security-service to validate
 *       tokens before injecting headers, ensuring authenticity.</li>
 * </ul>
 *
 * <h2>⚠️ Security Assumption</h2>
 * <p>
 * <b>This security model assumes services are NOT directly exposed to external networks.</b>
 * If services are exposed outside the Docker network, a malicious actor could inject
 * fake X-Authorities and X-User headers. In such cases, consider:
 * </p>
 * <ul>
 *   <li>Service mesh with mTLS (e.g., Istio, Linkerd)</li>
 *   <li>HMAC-signed headers at the gateway</li>
 *   <li>Re-validating tokens at each service (current pos-accounting pattern)</li>
 * </ul>
 */
public final class GatewaySecurityConstants {

    private GatewaySecurityConstants() {
        // Utility class
    }

    /**
     * Header containing comma-separated authorities/permissions.
     * Injected by pos-api-gateway after JWT validation.
     * Example: "ROLE_ADMIN,crm:party:view,crm:party:create"
     */
    public static final String HEADER_AUTHORITIES = "X-Authorities";

    /**
     * Header containing comma-separated role authorities.
     * Injected by pos-api-gateway after JWT validation.
     * Example: "ROLE_ADMIN,ROLE_MANAGER"
     */
    public static final String HEADER_ROLES = "X-Roles";

    /**
     * Header containing the Base64URL-encoded permission bitset forwarded by
     * the API gateway. Replaces the verbose {@link #HEADER_AUTHORITIES} CSV
     * for gateway-to-service traffic. Decoded by {@link GatewayAuthoritiesFilter}
     * using {@link DownstreamPermissionCatalog}.
     */
    public static final String HEADER_PERM_BITS = "X-Perm-Bits";

    /**
     * Header containing the integer permission catalog version that corresponds
     * to the {@link #HEADER_PERM_BITS} bitset. Must match
     * {@link DownstreamPermissionCatalog#CATALOG_VERSION} for the filter to
     * use the compact decode path.
     */
    public static final String HEADER_PERM_VER = "X-Perm-Ver";

    /**
     * Header containing the authenticated username/subject.
     * Injected by pos-api-gateway after JWT validation.
     */
    public static final String HEADER_USER = "X-User";

    /**
     * Header containing the original JWT token (optional, for audit/logging).
     * Not used for authentication - headers are trusted instead.
     */
    public static final String HEADER_TOKEN = "X-Token";

    /**
     * Default anonymous user when no authentication is present.
     */
    public static final String ANONYMOUS_USER = "anonymous";

    /**
     * Prefix for role-based authorities.
     */
    public static final String ROLE_PREFIX = "ROLE_";

    /**
     * Prefix used by the gateway when forwarding bitset-decoded permissions.
     */
    public static final String PERMISSION_PREFIX = "PERM_";

    /** Primary JWT claim key for stable user identifier. */
    public static final String CLAIM_UID = "uid";

    /** Legacy JWT claim key for stable user identifier. */
    public static final String CLAIM_USER_ID_LEGACY = "userId";

    /** Authentication details map key for stable user identifier. */
    public static final String DETAIL_USER_ID = "userId";

    /**
     * Authentication details map key for username/display principal.
     */
    public static final String DETAIL_USERNAME = "username";
}
