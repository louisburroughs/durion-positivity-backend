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
     * and {@code X-Roles}
     * headers are stripped before forwarding to downstream services.
     */
    private boolean stripInboundIdentityHeaders = true;

    /**
     * When true, requests whose inbound identity headers conflict with
     * token-derived identity are rejected.
     */
    private boolean rejectHeaderTokenMismatch = false;

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
}
