package com.positivity.mcp.internal.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.positivity.events.TimeConfig;
import com.positivity.time.ScaledClock;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;

class AcceleratedClockAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TimeConfig.class))
            .withUserConfiguration(ClockConsumerConfig.class);

    @Test
    void acceleratedProfileInjectsScaledClockIntoConsumerModule() {
        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=accelerated",
                        // Anchors are deployment-wide inputs: every accelerated JVM is handed the
                        // same real-start/virtual-start pair, so a consumer module never derives
                        // its own.
                        "pos.time.accelerated.real-start=2026-08-20T12:00:00Z",
                        "pos.time.accelerated.virtual-start=2025-08-20T12:00:00Z")
                .run(context -> {
                    assertThat(context).hasSingleBean(Clock.class);
                    assertThat(context.getBean(ClockConsumer.class).clock()).isInstanceOf(ScaledClock.class);
                });
    }

    record ClockConsumer(Clock clock) {}

    static class ClockConsumerConfig {

        @Bean
        ClockConsumer clockConsumer(Clock clock) {
            return new ClockConsumer(clock);
        }
    }
}
