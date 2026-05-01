package com.positivity.price.config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

/**
 * Fixes the Clock to 2026-01-01 UTC for all pos-price integration tests,
 * keeping offer date fixtures (e.g. 2026-04-01..2026-04-30) reliably in the future.
 */
@Configuration
@Profile("test")
public class TestClockConfiguration {

    @Bean(name = "testClock")
    @Primary
    public Clock testClock() {
        return Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    }
}
