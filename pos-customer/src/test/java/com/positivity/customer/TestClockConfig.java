package com.positivity.customer;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the application {@link Clock} to persistence slices.
 *
 * <p>{@code JpaAuditingConfig} feeds JPA auditing from the injected clock so audit timestamps follow
 * application time rather than wall time. A {@code @DataJpaTest} slice does load auto-configuration,
 * but only the persistence-related entries its slice filter admits; the shared clock from {@code
 * pos-events} is not among them, so each slice must supply one.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
