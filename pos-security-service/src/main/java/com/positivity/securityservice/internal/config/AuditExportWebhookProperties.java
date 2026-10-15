package com.positivity.securityservice.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How a {@code WEBHOOK} audit export is delivered to the tenant's registered endpoint (#2497).
 *
 * @param maxAttempts how many times one export is sent before the job fails (1-10); every attempt is
 *     re-signed and re-checks the destination
 * @param initialBackoff wait after the first failed attempt; each further wait doubles it. Zero
 *     retries immediately
 * @param connectTimeout TCP and TLS connect timeout of one attempt
 * @param readTimeout how long one attempt waits for the endpoint's response
 */
@ConfigurationProperties(prefix = "pos.security.audit-export.webhook")
public record AuditExportWebhookProperties(
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("PT5S") Duration initialBackoff,
        @DefaultValue("PT5S") Duration connectTimeout,
        @DefaultValue("PT30S") Duration readTimeout) {

    static final int MAX_ATTEMPTS_CEILING = 10;

    public AuditExportWebhookProperties {
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS_CEILING) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and " + MAX_ATTEMPTS_CEILING);
        }
        if (initialBackoff == null || initialBackoff.isNegative()) {
            throw new IllegalArgumentException("initialBackoff must be zero or a positive duration");
        }
        requirePositive(connectTimeout, "connectTimeout");
        requirePositive(readTimeout, "readTimeout");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
