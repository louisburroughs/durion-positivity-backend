package com.positivity.inventory.internal.config;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Tuning for the supplier availability-hint feed (CAP-322, #1312).
 *
 * <p>The staleness ceiling is the one number a caller's reading of a hint depends on, so it is
 * configuration rather than a constant: the meaningful ceiling is a function of how often we
 * actually poll a given vendor, and that is a per-binding schedule owned by pos-supplier which
 * pos-inventory cannot read. The default applies to every vendor; a vendor polled on a different
 * cadence gets an override keyed by its {@code vendorProfileId}:
 *
 * <pre>
 * pos:
 *   inventory:
 *     supplier-hints:
 *       staleness-ceiling: PT24H
 *       vendor-staleness-ceiling:
 *         "[018f0a1b-2c3d-7e4f-8a9b-0c1d2e3f4a5b]": PT6H
 * </pre>
 *
 * <p>Past the ceiling a hint reads as unknown, never as zero.
 */
@Component
@ConfigurationProperties(prefix = "pos.inventory.supplier-hints")
@Data
public class SupplierStockHintProperties {

    /** Age past which a hint's quantity is suppressed and it reads as unknown. */
    private Duration stalenessCeiling = Duration.ofHours(24);

    /** Per-vendor overrides of {@link #stalenessCeiling}, keyed by vendorProfileId. */
    private Map<String, Duration> vendorStalenessCeiling = new HashMap<>();

    /** Article resolution against pos-catalog's exact-match EAN lookup. */
    private Resolution resolution = new Resolution();

    /** The ceiling in force for one vendor: its override when configured, else the default. */
    public Duration ceilingFor(UUID vendorProfileId) {
        if (vendorProfileId == null) {
            return stalenessCeiling;
        }
        Duration override = vendorStalenessCeiling.get(vendorProfileId.toString());
        return override != null ? override : stalenessCeiling;
    }

    /** Resolution sweep settings. */
    @Data
    public static class Resolution {

        /**
         * Off by default: resolution reaches out to pos-catalog, and an environment without a
         * catalog to reach should store hints unresolved rather than log a failure per pass.
         */
        private boolean enabled = false;

        /** Hints resolved per pass. Bounds one pass's call volume against pos-catalog. */
        private int batchSize = 200;

        /** Delay between passes; mirrored by the {@code @Scheduled} placeholder on the sweep. */
        private long intervalMs = 300_000L;

        /** Delay before the first pass, so startup is not competing with it. */
        private long initialDelayMs = 60_000L;
    }
}
