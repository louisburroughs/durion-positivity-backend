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

    /** The floor and ceiling ADR-0062 §3 states; configuration may tighten them, never widen them. */
    static final int MIN_QUERY_LENGTH_FLOOR = 3;

    static final int MAX_RESULTS_CEILING = 10;

    public TenantSearchProperties {
        // These two values *are* the enumeration bound. Left open, a deployment override could
        // serve one-character queries or hand back more than ten organizations at a time, quietly
        // widening what the ADR fixed — so the bound is enforced here rather than documented.
        if (minQueryLength < MIN_QUERY_LENGTH_FLOOR) {
            throw new IllegalArgumentException("minQueryLength must be >= " + MIN_QUERY_LENGTH_FLOOR);
        }
        if (maxResults < 1 || maxResults > MAX_RESULTS_CEILING) {
            throw new IllegalArgumentException("maxResults must be between 1 and " + MAX_RESULTS_CEILING);
        }
    }
}
