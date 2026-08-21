package com.positivity.tax;

import java.time.Clock;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * Supplies the application {@link Clock} to persistence slices.
 *
 * <p>{@code JpaAuditingConfig} feeds JPA auditing from the injected clock so audit timestamps follow
 * application time rather than wall time. A {@code @DataJpaTest} slice loads no auto-configuration,
 * so the shared clock from {@code pos-events} is not present and each slice must supply one.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
