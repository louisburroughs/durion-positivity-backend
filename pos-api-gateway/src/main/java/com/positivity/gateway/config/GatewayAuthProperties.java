package com.positivity.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the gateway authentication filter.
 * <p>
 * Controls feature flags governing local JWT validation behaviour, inbound
 * identity header stripping, and header/token mismatch rejection.
 *
 * Issue: PERM-009
 */
@ConfigurationProperties(prefix = "auth")
public class GatewayAuthProperties {

    /**
     * When true, tokens without a {@code perm_bits} claim are rejected with 401.
     */
    private boolean tokenIdentityRequired = false;

    /**
     * When true, inbound {@code X-User}, {@code X-User-Id}, {@code X-Authorities},
     * {@code X-Perm-Bits}, {@code X-Perm-Ver}, and {@code X-Roles}
     * headers are stripped before forwarding to downstream services.
     */
    private boolean stripInboundIdentityHeaders = true;

    /**
     * When true, requests whose inbound identity headers conflict with
     * token-derived identity are rejected.
     */
    private boolean rejectHeaderTokenMismatch = false;

    /**
     * Root auth endpoint path that should bypass JWT auth checks.
     */
    private String authPathRoot;

    /**
     * Auth endpoint prefix that should bypass JWT auth checks.
     */
    private String authPathPrefix;

    /**
     * Root stripped auth endpoint path that should bypass JWT auth checks.
     */
    private String strippedAuthPathRoot;

    /**
     * Stripped auth endpoint prefix that should bypass JWT auth checks.
     */
    private String strippedAuthPathPrefix;

    public boolean isTokenIdentityRequired() {
        return tokenIdentityRequired;
    }

    public void setTokenIdentityRequired(boolean tokenIdentityRequired) {
        this.tokenIdentityRequired = tokenIdentityRequired;
    }

    public boolean isStripInboundIdentityHeaders() {
        return stripInboundIdentityHeaders;
    }

    public void setStripInboundIdentityHeaders(boolean stripInboundIdentityHeaders) {
        this.stripInboundIdentityHeaders = stripInboundIdentityHeaders;
    }

    public boolean isRejectHeaderTokenMismatch() {
        return rejectHeaderTokenMismatch;
    }

    public void setRejectHeaderTokenMismatch(boolean rejectHeaderTokenMismatch) {
        this.rejectHeaderTokenMismatch = rejectHeaderTokenMismatch;
    }

    public String getAuthPathRoot() {
        return authPathRoot;
    }

    public void setAuthPathRoot(String authPathRoot) {
        this.authPathRoot = authPathRoot;
    }

    public String getAuthPathPrefix() {
        return authPathPrefix;
    }

    public void setAuthPathPrefix(String authPathPrefix) {
        this.authPathPrefix = authPathPrefix;
    }

    public String getStrippedAuthPathRoot() {
        return strippedAuthPathRoot;
    }

    public void setStrippedAuthPathRoot(String strippedAuthPathRoot) {
        this.strippedAuthPathRoot = strippedAuthPathRoot;
    }

    public String getStrippedAuthPathPrefix() {
        return strippedAuthPathPrefix;
    }

    public void setStrippedAuthPathPrefix(String strippedAuthPathPrefix) {
        this.strippedAuthPathPrefix = strippedAuthPathPrefix;
    }
}
