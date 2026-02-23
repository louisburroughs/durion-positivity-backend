package com.positivity.inventory.internal.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public Clock auditingClock() {
        return Clock.systemUTC();
    }

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock auditingClock) {
        return () -> Optional.of(Instant.now(auditingClock));
    }
}
