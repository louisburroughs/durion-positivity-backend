package com.positivity.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ScaledClockTest {

    private static final Instant REAL_START = Instant.parse("2026-04-19T00:00:00Z");
    private static final Instant VIRTUAL_START = Instant.parse("2026-04-20T00:00:00Z");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @ParameterizedTest
    @MethodSource("invalidScales")
    void constructor_rejectsInvalidScale(double scale) {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, scale))
                .withMessage("scale must be a finite positive value");
    }

    @Test
    void constructor_rejectsNullBaseClock() {
        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new ScaledClock(null, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1000.0))
                .withMessage("baseClock");
    }

    @Test
    void constructor_rejectsNullZone() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);

        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new ScaledClock(baseClock, null, REAL_START, VIRTUAL_START, 1000.0))
                .withMessage("zone");
    }

    @Test
    void constructor_rejectsNullRealStart() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);

        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new ScaledClock(baseClock, ZoneOffset.UTC, null, VIRTUAL_START, 1000.0))
                .withMessage("realStart");
    }

    @Test
    void constructor_rejectsNullVirtualStart() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);

        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, null, 1000.0))
                .withMessage("virtualStart");
    }

    @Test
    void metricDayClock_usesRequestedZoneAndMetricScale() {
        ScaledClock clock = ScaledClock.metricDayClock(NEW_YORK);

        assertThat(clock.getZone()).isEqualTo(NEW_YORK);
        assertThat(clock.getScale()).isEqualTo(1000.0);
    }

    @Test
    void instant_advancesVirtualTimeByScaledRealElapsedTime() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);
        ScaledClock clock = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 10.0);

        baseClock.setInstant(REAL_START.plusSeconds(2));

        assertThat(clock.instant()).isEqualTo(VIRTUAL_START.plusSeconds(20));
    }

    @Test
    void instant_roundsScaledNanosecondsToNearestNanosecond() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);
        ScaledClock clock = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1.5);

        baseClock.setInstant(REAL_START.plusNanos(1));

        assertThat(clock.instant()).isEqualTo(VIRTUAL_START.plusNanos(2));
    }

    @Test
    void instant_supportsNegativeElapsedTimeWhenBaseClockMovesBeforeRealStart() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);
        ScaledClock clock = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 5.0);

        baseClock.setInstant(REAL_START.minusMillis(2));

        assertThat(clock.instant()).isEqualTo(VIRTUAL_START.minusMillis(10));
    }

    @Test
    void millis_returnsScaledInstantEpochMillis() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);
        ScaledClock clock = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 4.0);

        baseClock.setInstant(REAL_START.plusMillis(250));

        assertThat(clock.millis()).isEqualTo(VIRTUAL_START.plusSeconds(1).toEpochMilli());
    }

    @Test
    void withZone_returnsClockWithNewZoneAndSameTimingConfiguration() {
        MutableClock baseClock = new MutableClock(REAL_START.plusSeconds(3), ZoneOffset.UTC);
        ScaledClock clock = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 3.0);

        Clock changedZone = clock.withZone(NEW_YORK);

        assertThat(changedZone).isInstanceOf(ScaledClock.class);
        assertThat(changedZone.getZone()).isEqualTo(NEW_YORK);
        assertThat(changedZone.instant()).isEqualTo(clock.instant());
        assertThat(changedZone).isEqualTo(new ScaledClock(baseClock, NEW_YORK, REAL_START, VIRTUAL_START, 3.0));
    }

    @Test
    void withZone_rejectsNullZone() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);
        ScaledClock clock = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1000.0);

        assertThatExceptionOfType(NullPointerException.class)
                .isThrownBy(() -> clock.withZone(null))
                .withMessage("zone");
    }

    @Test
    void equalsAndHashCode_useClockConfiguration() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);
        ScaledClock first = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1000.0);
        ScaledClock second = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1000.0);

        assertThat(first).isEqualTo(first);
        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
        assertThat(first).isNotEqualTo(null).isNotEqualTo("scaled-clock");
    }

    @Test
    void equals_returnsFalseWhenBaseClockDiffers() {
        ScaledClock first = new ScaledClock(
                new MutableClock(REAL_START, ZoneOffset.UTC), ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1000.0);
        ScaledClock second = new ScaledClock(
                new MutableClock(REAL_START, ZoneOffset.UTC), ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1000.0);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void equals_returnsFalseWhenZoneDiffers() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);
        ScaledClock first = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1000.0);
        ScaledClock second = new ScaledClock(baseClock, NEW_YORK, REAL_START, VIRTUAL_START, 1000.0);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void equals_returnsFalseWhenRealStartDiffers() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);
        ScaledClock first = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1000.0);
        ScaledClock second = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START.plusNanos(1), VIRTUAL_START, 1000.0);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void equals_returnsFalseWhenVirtualStartDiffers() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);
        ScaledClock first = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1000.0);
        ScaledClock second = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START.plusNanos(1), 1000.0);

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void equals_returnsFalseWhenScaleDiffers() {
        MutableClock baseClock = new MutableClock(REAL_START, ZoneOffset.UTC);
        ScaledClock first = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 1000.0);
        ScaledClock second = new ScaledClock(baseClock, ZoneOffset.UTC, REAL_START, VIRTUAL_START, 60.0);

        assertThat(first).isNotEqualTo(second);
    }

    private static Stream<Double> invalidScales() {
        return Stream.of(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        private MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        private void setInstant(Instant instant) {
            this.instant = instant;
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
