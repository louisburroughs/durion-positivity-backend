package com.positivity.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.time.ScaledClock;
import com.positivity.time.TimeSource;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class TimeConfigTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(TimeConfig.class));

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
    void acceleratedProfile_exposesScaledClock() {
        contextRunner.withPropertyValues("spring.profiles.active=accelerated").run(context -> {
            assertThat(context).hasSingleBean(Clock.class);
            Clock clock = context.getBean(Clock.class);
            assertThat(clock).isInstanceOf(ScaledClock.class);
            assertThat(((ScaledClock) clock).getScale()).isEqualTo(1000.0);
            assertThat(clock.getZone()).isEqualTo(ZoneId.of("UTC"));
            assertThat(TimeSource.clock()).isSameAs(clock);
        });
    }

    @Test
    void acceleratedProfile_honorsConfiguredScaleAndZone() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=accelerated",
                        "pos.time.accelerated.scale=60.0",
                        "pos.time.accelerated.zone=America/New_York")
                .run(context -> {
                    assertThat(context).hasSingleBean(Clock.class);
                    Clock clock = context.getBean(Clock.class);
                    assertThat(clock).isInstanceOf(ScaledClock.class);
                    assertThat(((ScaledClock) clock).getScale()).isEqualTo(60.0);
                    assertThat(clock.getZone()).isEqualTo(ZoneId.of("America/New_York"));
                });
    }

    @Test
    void acceleratedProfile_rejectsInvalidScale() {
        contextRunner
                .withPropertyValues("spring.profiles.active=accelerated", "pos.time.accelerated.scale=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasRootCauseMessage("scale must be a finite positive value");
                });
    }
}
