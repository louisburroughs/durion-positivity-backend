package com.positivity.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class TimeSource {

    private static final AtomicReference<Clock> CLOCK = new AtomicReference<>(Clock.systemUTC());

    /**
     * Set once the accelerated profile is known to be active, before any bean is created.
     *
     * <p>Between class load and {@code TimeSourceBinder} running at
     * {@code afterSingletonsInstantiated}, the holder still carries the system clock. Under the
     * accelerated profile a read in that window would silently return wall time to an entity
     * callback and write a business timestamp a year ahead of every other row, so a pre-bind read is
     * made to fail loudly instead.
     */
    private static final AtomicBoolean STRICT = new AtomicBoolean(false);

    private static final AtomicBoolean BOUND = new AtomicBoolean(false);

    private TimeSource() {}

    public static Clock clock() {
        if (STRICT.get() && !BOUND.get()) {
            throw new IllegalStateException("TimeSource was read before the accelerated Clock was bound;"
                    + " this read would have silently returned wall time");
        }
        return CLOCK.get();
    }

    /**
     * Requires the accelerated clock to be bound before any read. Called from configuration while
     * the accelerated profile is active.
     */
    public static void requireBoundClock() {
        STRICT.set(true);
    }

    public static Instant instant() {
        return Instant.now(clock());
    }

    public static LocalDateTime localDateTime() {
        return LocalDateTime.now(clock());
    }

    public static void setClock(Clock clock) {
        CLOCK.set(Objects.requireNonNull(clock, "clock"));
        BOUND.set(true);
    }

    public static void reset() {
        CLOCK.set(Clock.systemUTC());
        BOUND.set(false);
        STRICT.set(false);
    }
}
