package com.positivity.securityservice.internal.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds on the public organization search (ADR-0062 §3).
 *
 * <p>The endpoint is reachable before authentication, because it feeds the login form, so it is a
 * tenant-enumeration surface by design. These are the limits that bound the exposure; {@code
 * enabled} is an incident lever — turning it off leaves login working for anyone who knows their
 * slug — not a production default.
 *
 * @param enabled whether the endpoint answers at all; disabled answers 404
 * @param minQueryLength shortest normalized query that is searched; shorter answers an empty list
 * @param maxResults most organizations ever returned for one query
 */
@ConfigurationProperties(prefix = "auth.tenant-search")
public record TenantSearchProperties(boolean enabled, int minQueryLength, int maxResults) {

    public TenantSearchProperties {
        if (minQueryLength < 1) {
            throw new IllegalArgumentException("minQueryLength must be >= 1");
        }
        if (maxResults < 1) {
            throw new IllegalArgumentException("maxResults must be >= 1");
        }
    }
}
