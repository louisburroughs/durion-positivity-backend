package com.positivity.time;

import java.time.Instant;
import java.time.ZoneId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Shared accelerated-clock anchors bound from {@code pos.time.accelerated.*}.
 *
 * <p>Every JVM in an accelerated deployment receives the <em>same</em> {@code real-start} and
 * {@code virtual-start} values, generated once immediately before deployment. A service must never
 * derive its own anchor: at scale 1000, one real second of startup skew becomes roughly 999 virtual
 * seconds of service-to-service skew.
 *
 * <p>Virtual time is defined as
 * {@code virtual(t) = min(virtualStart + scale * (now - realStart), now)} when {@code converge} is
 * enabled, so virtual time trails wall time, converges on it, and then ticks at scale 1 forever.
 * Because virtual time never exceeds wall time, an accelerated run cannot write future-dated
 * records.
 *
 * @param scale how many virtual seconds elapse per real second; must be finite and positive
 * @param zone the zone reported by the resulting {@link java.time.Clock}
 * @param realStart the wall-clock instant the accelerated run was launched
 * @param virtualStart the virtual instant the run starts from; must not be after {@code realStart}
 * @param converge whether the clock stops accelerating once it reaches wall time
 */
@ConfigurationProperties("pos.time.accelerated")
public record AcceleratedTimeProperties(
        @DefaultValue("1000.0") double scale,
        @DefaultValue("UTC") ZoneId zone,
        Instant realStart,
        Instant virtualStart,
        @DefaultValue("true") boolean converge) {

    public AcceleratedTimeProperties {
        if (scale <= 0.0 || Double.isNaN(scale) || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("pos.time.accelerated.scale must be a finite positive value");
        }
        if (zone == null) {
            throw new IllegalArgumentException("pos.time.accelerated.zone is required");
        }
        if (realStart == null) {
            throw new IllegalArgumentException("pos.time.accelerated.real-start is required");
        }
        if (virtualStart == null) {
            throw new IllegalArgumentException("pos.time.accelerated.virtual-start is required");
        }
        if (virtualStart.isAfter(realStart)) {
            // A virtual start ahead of the real start would write future-dated records,
            // expiries, and audit history into a shared environment.
            throw new IllegalArgumentException(
                    "pos.time.accelerated.virtual-start must not be after pos.time.accelerated.real-start");
        }
        if (converge && virtualStart.isBefore(realStart) && scale <= 1.0) {
            // Wall time advances during catch-up, so a gap only closes when scale exceeds 1.
            throw new IllegalArgumentException(
                    "pos.time.accelerated.scale must be greater than 1 to converge on wall time");
        }
    }
}
