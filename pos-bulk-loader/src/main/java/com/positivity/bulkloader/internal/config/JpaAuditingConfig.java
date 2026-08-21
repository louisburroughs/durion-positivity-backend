package com.positivity.bulkloader.internal.config;

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

    /**
     * Auditing timestamps come from the injected application {@link java.time.Clock}, not the system
     * clock. Spring Data's default provider reads wall time directly, which would leave this module's
     * {@code @CreatedDate}/{@code @LastModifiedDate} values on real time while the rest of the
     * deployment runs on the accelerated clock.
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
