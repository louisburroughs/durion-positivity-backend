package com.positivity.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.time.ScaledClock;
import com.positivity.time.TimeSource;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TimeConfigTest {

    private static final String REAL_START = "2026-08-20T12:00:00Z";
    private static final String VIRTUAL_START = "2025-08-20T12:00:00Z";

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TimeConfig.class));

    private ApplicationContextRunner accelerated(String... additionalProperties) {
        String[] properties = new String[additionalProperties.length + 1];
        properties[0] = "spring.profiles.active=accelerated";
        System.arraycopy(additionalProperties, 0, properties, 1, additionalProperties.length);
        return contextRunner.withPropertyValues(properties);
    }

    @Test
    void defaultProfile_exposesSystemUtcClock() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            Clock clock = context.getBean(Clock.class);
            assertThat(clock).isNotInstanceOf(ScaledClock.class);
            assertThat(clock.getZone()).isEqualTo(ZoneOffset.UTC);
            assertThat(TimeSource.clock()).isSameAs(clock);
        });
    }

    @Test
    void defaultProfile_doesNotRequireAcceleratedAnchors() {
        contextRunner.run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void acceleratedProfile_exposesSingleScaledClockBoundToSharedAnchors() {
        accelerated(
                        "pos.time.accelerated.scale=1000",
                        "pos.time.accelerated.zone=UTC",
                        "pos.time.accelerated.real-start=" + REAL_START,
                        "pos.time.accelerated.virtual-start=" + VIRTUAL_START,
                        "pos.time.accelerated.converge=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(Clock.class);
                    Clock clock = context.getBean(Clock.class);
                    assertThat(clock).isInstanceOf(ScaledClock.class);

                    ScaledClock scaledClock = (ScaledClock) clock;
                    assertThat(scaledClock.getScale()).isEqualTo(1000.0);
                    assertThat(scaledClock.getZone()).isEqualTo(ZoneId.of("UTC"));
                    assertThat(scaledClock.getRealStart()).isEqualTo(Instant.parse(REAL_START));
                    assertThat(scaledClock.getVirtualStart()).isEqualTo(Instant.parse(VIRTUAL_START));
                    assertThat(scaledClock.isConvergenceEnabled()).isTrue();
                    assertThat(TimeSource.clock()).isSameAs(clock);
                });
    }

    @Test
    void acceleratedProfile_exposesScaledClockAsItsConcreteType() {
        accelerated(
                        "pos.time.accelerated.real-start=" + REAL_START,
                        "pos.time.accelerated.virtual-start=" + VIRTUAL_START)
                .run(context -> assertThat(context).hasSingleBean(ScaledClock.class));
    }

    @Test
    void acceleratedProfile_honorsConfiguredScaleAndZone() {
        accelerated(
                        "pos.time.accelerated.scale=60.0",
                        "pos.time.accelerated.zone=America/New_York",
                        "pos.time.accelerated.real-start=" + REAL_START,
                        "pos.time.accelerated.virtual-start=" + VIRTUAL_START)
                .run(context -> {
                    ScaledClock clock = context.getBean(ScaledClock.class);
                    assertThat(clock.getScale()).isEqualTo(60.0);
                    assertThat(clock.getZone()).isEqualTo(ZoneId.of("America/New_York"));
                });
    }

    @Test
    void acceleratedProfile_defaultsConvergenceToEnabled() {
        accelerated(
                        "pos.time.accelerated.real-start=" + REAL_START,
                        "pos.time.accelerated.virtual-start=" + VIRTUAL_START)
                .run(context -> assertThat(context.getBean(ScaledClock.class).isConvergenceEnabled())
                        .isTrue());
    }

    @Test
    void acceleratedProfile_rejectsMissingRealStart() {
        accelerated("pos.time.accelerated.virtual-start=" + VIRTUAL_START).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage("pos.time.accelerated.real-start is required");
        });
    }

    @Test
    void acceleratedProfile_rejectsMissingVirtualStart() {
        accelerated("pos.time.accelerated.real-start=" + REAL_START).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("pos.time.accelerated.virtual-start is required");
        });
    }

    @Test
    void acceleratedProfile_rejectsVirtualStartAfterRealStart() {
        accelerated(
                        "pos.time.accelerated.real-start=" + VIRTUAL_START,
                        "pos.time.accelerated.virtual-start=" + REAL_START)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "pos.time.accelerated.virtual-start must not be after pos.time.accelerated.real-start");
                });
    }

    @Test
    void acceleratedProfile_rejectsInvalidScale() {
        accelerated(
                        "pos.time.accelerated.scale=0",
                        "pos.time.accelerated.real-start=" + REAL_START,
                        "pos.time.accelerated.virtual-start=" + VIRTUAL_START)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("pos.time.accelerated.scale must be a finite positive value");
                });
    }

    @Test
    void acceleratedProfile_rejectsScaleThatCanNeverConverge() {
        accelerated(
                        "pos.time.accelerated.scale=1",
                        "pos.time.accelerated.real-start=" + REAL_START,
                        "pos.time.accelerated.virtual-start=" + VIRTUAL_START)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage(
                                    "pos.time.accelerated.scale must be greater than 1 to converge on wall time");
                });
    }

    @Test
    void acceleratedProfile_rejectsInvalidZone() {
        accelerated(
                        "pos.time.accelerated.zone=Not/AZone",
                        "pos.time.accelerated.real-start=" + REAL_START,
                        "pos.time.accelerated.virtual-start=" + VIRTUAL_START)
                .run(context -> assertThat(context).hasFailed());
    }
}
