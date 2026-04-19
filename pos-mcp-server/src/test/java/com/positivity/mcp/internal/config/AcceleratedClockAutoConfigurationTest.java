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
        contextRunner.withPropertyValues("spring.profiles.active=accelerated").run(context -> {
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
