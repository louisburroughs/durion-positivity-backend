package com.positivity.securityservice.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds on asynchronous audit exports (#2408).
 *
 * @param maxRows most audit events one export may contain; a job whose filters match more fails
 *     with a message asking for narrower filters
 * @param staleAfter how long a job may stay PENDING or IN_PROGRESS before a read marks it FAILED
 *     as interrupted (a restart drops the in-flight work), so a poll never waits forever
 */
@ConfigurationProperties(prefix = "pos.security.audit-export")
public record AuditExportProperties(
        @DefaultValue("100000") int maxRows,
        @DefaultValue("PT30M") Duration staleAfter) {

    public AuditExportProperties {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be >= 1");
        }
        if (staleAfter == null || staleAfter.isNegative() || staleAfter.isZero()) {
            throw new IllegalArgumentException("staleAfter must be a positive duration");
        }
    }
}
