package com.positivity.time;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class TimeSource {

    private static final AtomicReference<Clock> CLOCK = new AtomicReference<>(Clock.systemUTC());

    private TimeSource() {}

    public static Clock clock() {
        return CLOCK.get();
    }

    public static Instant instant() {
        return Instant.now(clock());
    }

    public static LocalDateTime localDateTime() {
        return LocalDateTime.now(clock());
    }

    public static void setClock(Clock clock) {
        CLOCK.set(Objects.requireNonNull(clock, "clock"));
    }

    public static void reset() {
        CLOCK.set(Clock.systemUTC());
    }
}
