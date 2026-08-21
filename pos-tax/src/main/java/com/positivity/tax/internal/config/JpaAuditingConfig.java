package com.positivity.tax.internal.config;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * JPA auditing backed by the injected application {@link Clock} (ADR-0018/0024).
 *
 * <p>The provider is what keeps {@code created_at}/{@code updated_at} on application time rather
 * than wall time: under the accelerated profile the injected clock is the shared converging clock,
 * and PostgreSQL only stores what Java sends it.
 */
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(Instant.now(clock));
    }
}
