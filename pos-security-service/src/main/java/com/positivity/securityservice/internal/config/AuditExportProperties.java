package com.positivity.securityservice.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Bounds on asynchronous audit exports (#2408).
 *
 * @param maxRows most audit events one export may contain; a job whose filters match more fails
 *     with a message asking for narrower filters
 * @param maxBytes largest export file, in UTF-8 bytes, a job may render; audit values are unbounded
 *     text, so the row cap alone does not bound memory, and a larger file fails the job the same way
 * @param staleAfter how long a job may stay PENDING or IN_PROGRESS before the scheduled sweep marks
 *     it FAILED as interrupted (a restart drops the in-flight work), so a poll never waits forever
 * @param retention how long a job and its file are kept after it finished (or, never finished,
 *     after it was requested) before the scheduled sweep deletes them
 * @param sweepInterval fixed delay between two runs of the per-tenant sweep; read by the
 *     {@code @Scheduled} trigger through the same property
 */
@ConfigurationProperties(prefix = "pos.security.audit-export")
public record AuditExportProperties(
        @DefaultValue("100000") int maxRows,
        @DefaultValue("26214400") long maxBytes,
        @DefaultValue("PT30M") Duration staleAfter,
        @DefaultValue("P7D") Duration retention,
        @DefaultValue("PT5M") Duration sweepInterval) {

    public AuditExportProperties {
        if (maxRows < 1) {
            throw new IllegalArgumentException("maxRows must be >= 1");
        }
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be >= 1");
        }
        requirePositive(staleAfter, "staleAfter");
        requirePositive(retention, "retention");
        requirePositive(sweepInterval, "sweepInterval");
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isNegative() || value.isZero()) {
            throw new IllegalArgumentException(name + " must be a positive duration");
        }
    }
}
