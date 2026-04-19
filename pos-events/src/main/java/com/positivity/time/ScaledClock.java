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
 */
public final class ScaledClock extends Clock {
    private final Clock baseClock;
    private final ZoneId zone;
    private final Instant realStart;
    private final Instant virtualStart;
    private final double scale;

    public ScaledClock(Clock baseClock, ZoneId zone, Instant realStart, Instant virtualStart, double scale) {
        if (scale <= 0.0 || Double.isNaN(scale) || Double.isInfinite(scale)) {
            throw new IllegalArgumentException("scale must be a finite positive value");
        }

        this.baseClock = Objects.requireNonNull(baseClock, "baseClock");
        this.zone = Objects.requireNonNull(zone, "zone");
        this.realStart = Objects.requireNonNull(realStart, "realStart");
        this.virtualStart = Objects.requireNonNull(virtualStart, "virtualStart");
        this.scale = scale;
    }

    public static ScaledClock metricDayClock(ZoneId zone) {
        Clock base = Clock.systemUTC();
        Instant now = base.instant();
        return new ScaledClock(base, zone, now, now, 1000.0);
    }

    public double getScale() {
        return scale;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new ScaledClock(baseClock, zone, realStart, virtualStart, scale);
    }

    @Override
    public Instant instant() {
        Instant now = baseClock.instant();

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
                && baseClock.equals(other.baseClock)
                && zone.equals(other.zone)
                && realStart.equals(other.realStart)
                && virtualStart.equals(other.virtualStart);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseClock, zone, realStart, virtualStart, scale);
    }

    private static long safeToEpochNanos(Instant instant) {
        long seconds = instant.getEpochSecond();
        int nanos = instant.getNano();

        // This is fine for practical modern dates.
        // For extreme ranges, use BigInteger instead.
        return Math.addExact(Math.multiplyExact(seconds, 1_000_000_000L), nanos);
    }
}
