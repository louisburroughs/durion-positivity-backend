package com.positivity.time;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;

/**
 * A clock whose returned Instant advances faster than real time.
 *
 * Example:
 * scale = 1000.0 means 1 real second -> 1000 virtual seconds
 * which also means 1 real millisecond -> 1 virtual second
 *
 * <p>When convergence is enabled the clock is a <em>converging</em> clock:
 * {@code instant()} returns {@code min(virtualStart + scale * (now - realStart), now)}. Started
 * from a virtual anchor in the past it therefore trails wall time, closes the gap in
 * {@code gap / (scale - 1)} real time, and from then on reports ordinary system time forever.
 * Convergence is latched, so a base clock that jumps backwards can never rewind the virtual clock
 * below wall time.
 */
public final class ScaledClock extends Clock {
    private final Clock baseClock;
    private final ZoneId zone;
    private final Instant realStart;
    private final Instant virtualStart;
    private final double scale;
    private final boolean converge;

    private volatile boolean converged;

    /**
     * Creates a clock that accelerates without bound and never converges on wall time.
     */
    public ScaledClock(Clock baseClock, ZoneId zone, Instant realStart, Instant virtualStart, double scale) {
        this(baseClock, zone, realStart, virtualStart, scale, false);
    }

    /**
     * Creates a clock that optionally stops accelerating once virtual time reaches wall time.
     *
     * @param converge when true, {@code instant()} is clamped to the base clock's instant
     */
    public ScaledClock(
            Clock baseClock, ZoneId zone, Instant realStart, Instant virtualStart, double scale, boolean converge) {
        if (scale <= 0.0 || Double.isNaN(scale) || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("scale must be a finite positive value");
        }

        this.baseClock = Objects.requireNonNull(baseClock, "baseClock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.realStart = Objects.requireNonNull(realStart, "realStart");
        this.virtualStart = Objects.requireNonNull(virtualStart, "virtualStart");
        this.scale = scale;
        this.converge = converge;
    }

    public static ScaledClock metricDayClock(ZoneId zone) {
        Clock base = Clock.systemUTC();
        Instant now = base.instant();
        return new ScaledClock(base, zone, now, now, 1000.0);
    }

    public double getScale() {
        return scale;
    }

    /** The wall-clock instant this clock was anchored to. */
    public Instant getRealStart() {
        return realStart;
    }

    /** The virtual instant this clock started from. */
    public Instant getVirtualStart() {
        return virtualStart;
    }

    /** Whether this clock is configured to stop accelerating once it reaches wall time. */
    public boolean isConvergenceEnabled() {
        return converge;
    }

    /**
     * Whether virtual time has caught up with wall time. Always false when convergence is disabled.
     * Once true, it stays true for the lifetime of this clock.
     */
    public boolean isConverged() {
        if (!converge) {
            return false;
        }
        if (converged) {
            return true;
        }
        Instant now = baseClock.instant();
        if (!scaledInstant(now).isBefore(now)) {
            converged = true;
        }
        return converged;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new ScaledClock(baseClock, zone, realStart, virtualStart, scale, converge);
    }

    @Override
    public Instant instant() {
        Instant now = baseClock.instant();

        if (!converge) {
            return scaledInstant(now);
        }
        if (converged) {
            return now;
        }

        Instant scaled = scaledInstant(now);
        if (!scaled.isBefore(now)) {
            converged = true;
            return now;
        }
        return scaled;
    }

    private Instant scaledInstant(Instant now) {
        long realStartNanos = safeToEpochNanos(realStart);
        long nowNanos = safeToEpochNanos(now);

        long realElapsedNanos = nowNanos - realStartNanos;
        long virtualElapsedNanos = Math.round(realElapsedNanos * scale);

        return virtualStart.plusNanos(virtualElapsedNanos);
    }

    @Override
    public long millis() {
        return instant().toEpochMilli();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof ScaledClock other)) {
            return false;
        }
        return Double.compare(scale, other.scale) == 0
                && converge == other.converge
                && baseClock.equals(other.baseClock)
                && zone.equals(other.zone)
                && realStart.equals(other.realStart)
                && virtualStart.equals(other.virtualStart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseClock, zone, realStart, virtualStart, scale, converge);
    }

    private static long safeToEpochNanos(Instant instant) {
        long seconds = instant.getEpochSecond();
        int nanos = instant.getNano();

        // This is fine for practical modern dates.
        // For extreme ranges, use BigInteger instead.
        return Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), nanos);
    }
}
