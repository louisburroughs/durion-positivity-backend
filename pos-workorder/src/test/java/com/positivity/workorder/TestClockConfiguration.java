package com.positivity.workorder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Provides a deterministic clock for integration tests running with the test profile.
 */
@Configuration
@Profile("test")
public class TestClockConfiguration {

    @Bean(name = "auditingClock")
    @Primary
    public Clock testClock() {
        return Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
    }
}
