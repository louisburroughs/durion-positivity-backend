package com.positivity.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TimeSourceTest {

    private static final Instant VIRTUAL_NOW = Instant.parse("2025-08-20T12:00:00Z");

    @AfterEach
    void tearDown() {
        TimeSource.reset();
    }

    @Test
    void clock_defaultsToTheSystemClockWhenStrictModeIsOff() {
        assertThat(TimeSource.clock()).isNotNull();
        assertThat(TimeSource.instant()).isNotNull();
    }

    @Test
    void clock_failsOnAPreBindReadOnceTheAcceleratedProfileRequiresABoundClock() {
        TimeSource.requireBoundClock();

        // Silently returning wall time here is what would write a business timestamp a year ahead
        // of every other row in the deployment.
        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(TimeSource::clock)
                .withMessageContaining("before the accelerated Clock was bound");
    }

    @Test
    void clock_returnsTheBoundClockAfterBinding() {
        TimeSource.requireBoundClock();
        TimeSource.setClock(Clock.fixed(VIRTUAL_NOW, ZoneOffset.UTC));

        assertThat(TimeSource.instant()).isEqualTo(VIRTUAL_NOW);
    }

    @Test
    void reset_clearsStrictModeAndTheBoundClock() {
        TimeSource.requireBoundClock();
        TimeSource.setClock(Clock.fixed(VIRTUAL_NOW, ZoneOffset.UTC));

        TimeSource.reset();

        assertThat(TimeSource.instant()).isAfter(VIRTUAL_NOW);
    }
}
